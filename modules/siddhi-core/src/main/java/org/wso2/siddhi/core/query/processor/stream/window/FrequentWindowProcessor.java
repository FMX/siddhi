/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.core.query.processor.stream.window;

import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.core.config.ExecutionPlanContext;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.state.StateEvent;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventCloner;
import org.wso2.siddhi.core.executor.ConstantExpressionExecutor;
import org.wso2.siddhi.core.executor.ExpressionExecutor;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.query.processor.Processor;
import org.wso2.siddhi.core.table.EventTable;
import org.wso2.siddhi.core.util.collection.operator.Finder;
import org.wso2.siddhi.core.util.collection.operator.MatchingMetaStateHolder;
import org.wso2.siddhi.core.util.parser.OperatorParser;
import org.wso2.siddhi.query.api.expression.Expression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is the implementation of a counting algorithm based on
 * Misra-Gries counting algorithm
 */
//@Description("This window returns the latest events with the most frequently " +
//        "occurred value for a given attribute(s). Frequency calculation for this " +
//        "window processor is based on Misra-Gries counting algorithm.")
//@Parameters({
//        @Parameter(name = "eventCount", type = {DataType.INT}),
//        @Parameter(name = "attribute1", type = {DataType.STRING}, optional = true),
//        @Parameter(name = "attribute2", type = {DataType.STRING}, optional = true)
//})
@Extension(
        name = "frequent",
        namespace = "",
        description = "",
        parameters = {}
)
public class FrequentWindowProcessor extends WindowProcessor implements FindableProcessor {
    private ConcurrentHashMap<String, Integer> countMap = new ConcurrentHashMap<String, Integer>();
    private ConcurrentHashMap<String, StreamEvent> map = new ConcurrentHashMap<String, StreamEvent>();
    private VariableExpressionExecutor[] variableExpressionExecutors;

    private int mostFrequentCount;

    @Override
    protected void init(ExpressionExecutor[] attributeExpressionExecutors, ExecutionPlanContext executionPlanContext) {
        mostFrequentCount = Integer.parseInt(String.valueOf(((ConstantExpressionExecutor) attributeExpressionExecutors[0]).getValue()));
        variableExpressionExecutors = new VariableExpressionExecutor[attributeExpressionExecutors.length - 1];
        for (int i = 1; i < attributeExpressionExecutors.length; i++) {
            variableExpressionExecutors[i - 1] = (VariableExpressionExecutor) attributeExpressionExecutors[i];
        }
    }

    @Override
    protected void process(ComplexEventChunk<StreamEvent> streamEventChunk, Processor nextProcessor, StreamEventCloner streamEventCloner) {
        synchronized (this) {
            StreamEvent streamEvent = streamEventChunk.getFirst();
            streamEventChunk.clear();
            long currentTime = executionPlanContext.getTimestampGenerator().currentTime();
            while (streamEvent != null) {
                StreamEvent next = streamEvent.getNext();
                streamEvent.setNext(null);

                StreamEvent clonedEvent = streamEventCloner.copyStreamEvent(streamEvent);
                clonedEvent.setType(StreamEvent.Type.EXPIRED);

                String key = generateKey(streamEvent);
                StreamEvent oldEvent = map.put(key, clonedEvent);
                if (oldEvent != null) {
                    countMap.put(key, countMap.get(key) + 1);
                    streamEventChunk.add(streamEvent);
                } else {
                    //  This is a new event
                    if (map.size() > mostFrequentCount) {
                        List<String> keys = new ArrayList<String>(countMap.keySet());
                        for (int i = 0; i < mostFrequentCount; i++) {
                            int count = countMap.get(keys.get(i)) - 1;
                            if (count == 0) {
                                countMap.remove(keys.get(i));
                                StreamEvent expiredEvent = map.remove(keys.get(i));
                                expiredEvent.setTimestamp(currentTime);
                                streamEventChunk.add(expiredEvent);
                            } else {
                                countMap.put(keys.get(i), count);
                            }
                        }
                        // now we have tried to remove one for newly added item
                        if (map.size() > mostFrequentCount) {
                            //nothing happend by the attempt to remove one from the
                            // map so we are ignoring this event
                            map.remove(key);
                            // Here we do nothing just drop the message
                        } else {
                            // we got some space, event is already there in map object
                            // we just have to add it to the countMap
                            countMap.put(key, 1);
                            streamEventChunk.add(streamEvent);
                        }
                    } else {
                        countMap.put(generateKey(streamEvent), 1);
                        streamEventChunk.add(streamEvent);
                    }
                }
                streamEvent = next;
            }
        }
        nextProcessor.process(streamEventChunk);
    }

    @Override
    public void start() {
        //Do nothing
    }

    @Override
    public void stop() {
        //Do nothing
    }

    @Override
    public Map<String, Object> currentState() {
        Map<String, Object> state = new HashMap<>();
        state.put("CountMap", countMap);
        return state;
    }

    @Override
    public void restoreState(Map<String, Object> state) {
        countMap = (ConcurrentHashMap<String, Integer>) state.get("CountMap");
    }

    private String generateKey(StreamEvent event) {      // for performance reason if its all attribute we don't do the attribute list check
        StringBuilder stringBuilder = new StringBuilder();
        if (variableExpressionExecutors.length == 0) {
            for (Object data : event.getOutputData()) {
                stringBuilder.append(data);
            }
        } else {
            for (VariableExpressionExecutor executor : variableExpressionExecutors) {
                stringBuilder.append(event.getAttribute(executor.getPosition()));
            }
        }
        return stringBuilder.toString();
    }

    @Override
    public synchronized StreamEvent find(StateEvent matchingEvent, Finder finder) {
        return finder.find(matchingEvent, map.values(), streamEventCloner);
    }

    @Override
    public Finder constructFinder(Expression expression, MatchingMetaStateHolder matchingMetaStateHolder, ExecutionPlanContext executionPlanContext,
                                  List<VariableExpressionExecutor> variableExpressionExecutors, Map<String, EventTable> eventTableMap) {
        return OperatorParser.constructOperator(map.values(), expression, matchingMetaStateHolder, executionPlanContext, variableExpressionExecutors, eventTableMap, queryName);
    }
}
