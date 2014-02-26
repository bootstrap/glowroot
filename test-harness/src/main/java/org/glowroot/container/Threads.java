/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.container;

import java.lang.Thread.State;
import java.util.Collection;
import java.util.List;

import checkers.igj.quals.ReadOnly;
import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import dataflow.quals.Pure;

import org.glowroot.markers.Static;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class Threads {

    private Threads() {}

    public static List<Thread> currentThreads() {
        List<Thread> threads = Lists.newArrayList();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            // DestroyJavaVM is a JVM thread that appears sporadically, easier to just filter it out
            //
            // AWT-AppKit is a JVM thread on OS X that appears during webdriver tests
            //
            // "process reaper" are JVM threads on linux that monitors subprocesses, these use a
            // thread pool in jdk7 and so the threads stay around in the pool even after the
            // subprocess ends, they show up here when mixing local container and javaagent
            // container tests since javaagent container tests create subprocesses and then local
            // container tests check for rogue threads and find these
            if (thread.getState() != State.TERMINATED
                    && !thread.getName().equals("DestroyJavaVM")
                    && !thread.getName().equals("AWT-AppKit")
                    && !thread.getName().equals("process reaper")) {
                threads.add(thread);
            }
        }
        return threads;
    }

    // ensure the test didn't create any non-daemon threads
    public static void preShutdownCheck(@ReadOnly Collection<Thread> preExistingThreads)
            throws InterruptedException {
        // give the test 5 seconds to shutdown any threads they may have created, e.g. give tomcat
        // time to shutdown when testing tomcat plugin
        Stopwatch stopwatch = Stopwatch.createStarted();
        List<Thread> nonPreExistingThreads;
        List<Thread> rogueThreads;
        do {
            nonPreExistingThreads = getNonPreExistingThreads(preExistingThreads);
            rogueThreads = Lists.newArrayList();
            for (Thread thread : nonPreExistingThreads) {
                if (isRogueThread(thread)) {
                    rogueThreads.add(thread);
                }
            }
            // check total number of threads to make sure Glowroot is not creating too many
            //
            // currently, the seven threads are:
            //
            // Glowroot-Background-0
            // Glowroot-Background-1
            // H2 Log Writer GLOWROOT
            // H2 File Lock Watchdog <lock db file>
            // Glowroot-Http-Boss
            // Glowroot-Http-Worker-0
            // Generate Seed
            if (rogueThreads.isEmpty() && nonPreExistingThreads.size() <= 7) {
                // success
                return;
            }
            // wait a few milliseconds before trying again
            Thread.sleep(10);
        } while (stopwatch.elapsed(SECONDS) < 5);
        // failure
        if (!rogueThreads.isEmpty()) {
            throw new RogueThreadsException(rogueThreads);
        } else {
            throw new TooManyThreadsException(nonPreExistingThreads);
        }
    }

    // ensure the test shutdown all threads that it created
    public static void postShutdownCheck(@ReadOnly Collection<Thread> preExistingThreads)
            throws InterruptedException {
        // give it 5 seconds to shutdown threads
        Stopwatch stopwatch = Stopwatch.createStarted();
        List<Thread> rogueThreads;
        do {
            rogueThreads = getNonPreExistingThreads(preExistingThreads);
            if (rogueThreads.isEmpty()) {
                // success
                return;
            }
            // make an exception for H2's Generate Seed thread since it can take a bit of time to
            // complete on some systems (e.g. travis-ci), but is otherwise harmless
            if (rogueThreads.size() == 1
                    && rogueThreads.get(0).getName().equals(getGenerateSeedThreadName())) {
                // success
                return;
            }
            // wait a few milliseconds before trying again
            Thread.sleep(10);
        } while (stopwatch.elapsed(SECONDS) < 5);
        // failure
        throw new RogueThreadsException(rogueThreads);
    }

    // try to handle under- and over- sleeping for tests that depend on more accurate sleep timing
    public static void moreAccurateSleep(int millis) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        if (millis > 10) {
            Thread.sleep(millis - 10);
        }
        while (stopwatch.elapsed(MILLISECONDS) < millis) {
            Thread.sleep(1);
        }
    }

    private static List<Thread> getNonPreExistingThreads(
            @ReadOnly Collection<Thread> preExistingThreads) {
        List<Thread> currentThreads = currentThreads();
        currentThreads.removeAll(preExistingThreads);
        // remove current thread in case it is newly created by the tests
        // (e.g. SocketCommandProcessor)
        currentThreads.remove(Thread.currentThread());
        return currentThreads;
    }

    private static boolean isRogueThread(Thread thread) {
        if (!thread.isDaemon()) {
            return true;
        } else if (isShaded() && !thread.getName().startsWith("Glowroot-")) {
            return true;
        } else if (!isShaded()
                && !thread.getName().startsWith("Glowroot-")
                && !thread.getName().startsWith("H2 File Lock Watchdog ")
                && !thread.getName().startsWith("H2 Log Writer ")
                && !thread.getName().equals("Generate Seed")) {
            // note: Generate Seed is an H2 thread that generates a secure random seed
            // this can take a bit of time to complete on some systems (e.g. travis-ci), but is
            // otherwise harmless (see org.h2.util.MathUtils)
            return true;
        }
        return false;
    }

    private static String getGenerateSeedThreadName() {
        if (isShaded()) {
            return "Glowroot-H2 Generate Seed";
        } else {
            return "Generate Seed";
        }
    }

    private static boolean isShaded() {
        try {
            Class.forName("org.glowroot.shaded.slf4j.Logger");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @SuppressWarnings("serial")
    public static class RogueThreadsException extends ThreadsException {
        private RogueThreadsException(Collection<Thread> threads) {
            super(threads);
        }
    }

    @SuppressWarnings("serial")
    public static class TooManyThreadsException extends ThreadsException {
        private TooManyThreadsException(Collection<Thread> threads) {
            super(threads);
        }
    }

    @SuppressWarnings("serial")
    public static class ThreadsException extends RuntimeException {
        private final Collection<Thread> threads;
        private ThreadsException(Collection<Thread> threads) {
            this.threads = threads;
        }
        @Override
        @Pure
        public String getMessage() {
            StringBuilder sb = new StringBuilder();
            for (Thread thread : threads) {
                sb.append(threadToString(thread));
                sb.append("\n");
            }
            return sb.toString();
        }
        private static String threadToString(Thread thread) {
            ToStringHelper toStringHelper = Objects.toStringHelper(thread)
                    .add("name", thread.getName())
                    .add("class", thread.getClass().getName())
                    .add("state", thread.getState());
            for (int i = 0; i < Math.min(30, thread.getStackTrace().length); i++) {
                toStringHelper.add("stackTrace." + i, thread.getStackTrace()[i].toString());
            }
            return toStringHelper.toString();
        }
    }
}
