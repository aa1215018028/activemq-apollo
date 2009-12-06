/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.dispatch;

import java.util.concurrent.CountDownLatch;

import org.apache.activemq.dispatch.internal.RunnableCountDownLatch;
import org.apache.activemq.dispatch.internal.advanced.AdvancedDispatcher;
import org.apache.activemq.dispatch.internal.simple.SimpleDispatcher;

import static org.apache.activemq.dispatch.DispatchPriority.*;

import static java.lang.String.*;

/**
 * 
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class DispatchSystemTest {

    public static void main(String[] args) throws Exception {
        Dispatcher advancedSystem = new AdvancedDispatcher(new DispatcherConfig());
        advancedSystem.retain();
        benchmark("advanced global queue", advancedSystem, advancedSystem.getGlobalQueue(DEFAULT));
        benchmark("advanced private serial queue", advancedSystem, advancedSystem.createSerialQueue("test", DispatchOption.STICK_TO_CALLER_THREAD));

        RunnableCountDownLatch latch = new RunnableCountDownLatch(1);
        advancedSystem.addShutdownWatcher(latch);
        advancedSystem.release();
        latch.await();

        Dispatcher simpleSystem = new SimpleDispatcher(new DispatcherConfig());
        simpleSystem.retain();
        
        benchmark("simple global queue", simpleSystem, simpleSystem.getGlobalQueue(DEFAULT));
        benchmark("simple private serial queue", simpleSystem, simpleSystem.createSerialQueue("test", DispatchOption.STICK_TO_CALLER_THREAD));

        latch = new RunnableCountDownLatch(1);
        advancedSystem.addShutdownWatcher(latch);
        advancedSystem.release();
        latch.await();
    }

    private static void benchmark(String name, Dispatcher dispatcher, DispatchQueue queue) throws InterruptedException {
        // warm the JIT up..
        benchmarkWork(dispatcher, queue, 100000);
        
        int iterations = 1000*1000*20;
        long start = System.nanoTime();
        benchmarkWork(dispatcher, queue, iterations);
        long end = System.nanoTime();
        
        double durationMS = 1.0d*(end-start)/1000000d;
        double rate = 1000d * iterations / durationMS;
        
        System.out.println(format("name: %s, duration: %,.3f ms, rate: %,.2f executions/sec", name, durationMS, rate));
    }

    private static void benchmarkWork(final Dispatcher dispatcher, final DispatchQueue queue, int iterations) throws InterruptedException {
        final CountDownLatch counter = new CountDownLatch(iterations);
        Runnable task = new Runnable(){
            public void run() {
                counter.countDown();
                if( counter.getCount()>0 ) {
                    dispatcher.getCurrentQueue().dispatchAsync(this);
                }
            }
        };
        for (int i = 0; i < 1000; i++) {
            queue.dispatchAsync(task);
        }
        counter.await();
    }
}
