/*
 * Copyright (c) 2013-2015 Cinchapi Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cinchapi.concourse.thrift;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.server.ServerContext;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TServerEventHandler;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.cinchapi.concourse.util.Logger;

/**
 * An implementation of {@link TServer} that is based on
 * {@link org.apache.thrift.server.TThreadPoolServer TThreadPoolServer} but also
 * specially designed to handle special semantics of
 * {@link org.cinchapi.concourse.server.ConcourseServer ConcourseServer}.
 * 
 * @author Jeff Nelson
 */
public class ConcourseThriftServer extends TServer {

    /**
     * Create the default executor service implementation to use.
     * 
     * @param args
     * @return the ExecutorService
     */
    private static ExecutorService createDefaultExecutorService(Args args) {
        SynchronousQueue<Runnable> executorQueue = new SynchronousQueue<Runnable>();
        return new ThreadPoolExecutor(args.minWorkerThreads,
                args.maxWorkerThreads, 60, TimeUnit.SECONDS, executorQueue);
    }

    /**
     * The executor service for handling client connections.
     */
    private ExecutorService executor;

    /**
     * A flag that indicates whether the server is stopped or not.
     */
    private volatile boolean stopped;

    /**
     * The unit of measurement to use when determining how long to wait
     * while the server is stopping.
     */
    private final TimeUnit stopTimeoutUnit;

    /**
     * The unit of measurement to use when determining how long to wait
     * while the server is stopping.
     */
    private final long stopTimeoutVal;

    /**
     * Construct a new instance.
     * 
     * @param args
     */
    public ConcourseThriftServer(Args args) {
        super(args);
        stopTimeoutUnit = args.stopTimeoutUnit;
        stopTimeoutVal = args.stopTimeoutVal;
        executor = args.executorService != null ? args.executorService
                : createDefaultExecutorService(args);
    }

    /**
     * Start serving requests.
     */
    @SuppressWarnings("unused")
    public void serve() {
        try {
            serverTransport_.listen();
        }
        catch (TTransportException ttx) {
            Logger.error("Error occurred during listening.", ttx);
            return;
        }

        // Run the preServe event
        if(eventHandler_ != null) {
            eventHandler_.preServe();
        }

        stopped = false;
        setServing(true);
        while (!stopped) {
            int failureCount = 0;
            try {
                TTransport client = serverTransport_.accept();
                WorkerProcess wp = new WorkerProcess(client);
                executor.execute(wp);
            }
            catch (TTransportException ttx) {
                if(!stopped) {
                    ++failureCount;
                    Logger.warn("Transport error occurred during "
                            + "acceptance of message.", ttx);
                }
            }
        }

        executor.shutdown();

        // Loop until awaitTermination finally does return without a interrupted
        // exception. If we don't do this, then we'll shut down prematurely. We
        // want to let the executorService clear it's task queue, closing client
        // sockets appropriately.
        long timeoutMS = stopTimeoutUnit.toMillis(stopTimeoutVal);
        long now = System.currentTimeMillis();
        while (timeoutMS >= 0) {
            try {
                executor.awaitTermination(timeoutMS, TimeUnit.MILLISECONDS);
                break;
            }
            catch (InterruptedException ix) {
                long newnow = System.currentTimeMillis();
                timeoutMS -= (newnow - now);
                now = newnow;
            }
        }
        setServing(false);
    }

    /**
     * Stop the server.
     */
    public void stop() {
        stopped = true;
        serverTransport_.interrupt();
    }

    /**
     * The arguments that are passed to the
     * {@link ConcourseThriftServer#ConcourseThriftServer(Args) constructor} of
     * the {@link ConcourseThriftServer} class.
     * 
     * @author Jeff Nelson
     */
    public static class Args extends AbstractServerArgs<Args> {

        /**
         * The executor service to use for client connections.
         */
        public ExecutorService executorService;

        /**
         * The maximum number of worker threads to keep in the executor service.
         */
        public int maxWorkerThreads = Integer.MAX_VALUE;

        /**
         * The minimum number of worker threads to keep in the executor service.
         */
        public int minWorkerThreads = 5;

        /**
         * The unit of measurement to use when determining how long to wait
         * while the server is stopping.
         */
        public TimeUnit stopTimeoutUnit = TimeUnit.SECONDS;

        /**
         * The number of {@link #stopTimeoutUnit} to wait while the server is
         * stopping.
         */
        public long stopTimeoutVal = 60;

        /**
         * Construct a new instance.
         * 
         * @param transport
         */
        public Args(TServerTransport transport) {
            super(transport);
        }

        /**
         * Set {@link #executorService}.
         * 
         * @param executorService
         * @return {@link Args}
         */
        public Args executorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        /**
         * Set {@link #maxWorkerThreads}.
         * 
         * @param n
         * @return {@link Args}
         */
        public Args maxWorkerThreads(int n) {
            maxWorkerThreads = n;
            return this;
        }

        /**
         * Set {@link #minWorkerThreads}.
         * 
         * @param n
         * @return {@link Args}
         */
        public Args minWorkerThreads(int n) {
            minWorkerThreads = n;
            return this;
        }
    }

    private class WorkerProcess implements Runnable {

        /**
         * Client that this services.
         */
        private TTransport client;

        /**
         * Default constructor.
         *
         * @param client Transport to process
         */
        private WorkerProcess(TTransport client) {
            this.client = client;
        }

        /**
         * Loops on processing a client forever
         */
        public void run() {
            TProcessor processor = null;
            TTransport intrans = null;
            TTransport outtrans = null;
            TProtocol inproto = null;
            TProtocol outproto = null;

            TServerEventHandler eventHandler = null;
            ServerContext connectionContext = null;

            try {
                processor = processorFactory_.getProcessor(client);
                intrans = inputTransportFactory_.getTransport(client);
                outtrans = outputTransportFactory_.getTransport(client);
                inproto = inputProtocolFactory_.getProtocol(intrans);
                outproto = outputProtocolFactory_.getProtocol(outtrans);

                eventHandler = getEventHandler();
                if(eventHandler != null) {
                    connectionContext = eventHandler.createContext(inproto,
                            outproto);
                }
                // we check stopped_ first to make sure we're not supposed to be
                // shutting down. this is necessary for graceful shutdown.
                while (true) {

                    if(eventHandler != null) {
                        eventHandler.processContext(connectionContext, intrans,
                                outtrans);
                    }

                    if(stopped || !processor.process(inproto, outproto)) {
                        break;
                    }

                }
            }
            catch (TTransportException ttx) {
                // Assume the client died and continue silently
            }
            catch (TException tx) {
                Logger.error("Thrift error occurred during processing "
                        + "of message.", tx);
            }
            catch (Exception x) {
                Logger.error("Error occurred during processing of message.", x);
            }

            if(eventHandler != null) {
                eventHandler
                        .deleteContext(connectionContext, inproto, outproto);
            }

            if(intrans != null) {
                intrans.close();
            }

            if(outtrans != null) {
                outtrans.close();
            }
        }
    }

}
