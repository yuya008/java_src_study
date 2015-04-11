

package java.util.concurrent;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.security.AccessControlContext;
import java.security.ProtectionDomain;
import java.security.Permissions;

@sun.misc.Contended
public class ForkJoinPool extends AbstractExecutorService {



    private static void checkPermission() {
        SecurityManager security = System.getSecurityManager();
        if (security != null)
            security.checkPermission(modifyThreadPermission);
    }


    public static interface ForkJoinWorkerThreadFactory {
        public ForkJoinWorkerThread newThread(ForkJoinPool pool);
    }

    static final class DefaultForkJoinWorkerThreadFactory
        implements ForkJoinWorkerThreadFactory {
        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return new ForkJoinWorkerThread(pool);
        }
    }

    static final class EmptyTask extends ForkJoinTask<Void> {
        private static final long serialVersionUID = -7721805057305804111L;
        EmptyTask() { status = ForkJoinTask.NORMAL; } // force done
        public final Void getRawResult() { return null; }
        public final void setRawResult(Void x) {}
        public final boolean exec() { return true; }
    }

    @sun.misc.Contended
    static final class WorkQueue {
        static final int INITIAL_QUEUE_CAPACITY = 1 << 13;

        static final int MAXIMUM_QUEUE_CAPACITY = 1 << 26; // 64M

        volatile int eventCount;   // encoded inactivation count; < 0 if inactive
        int nextWait;              // encoded record of next event waiter
        int nsteals;               // number of steals
        int hint;                  // steal index hint
        short poolIndex;           // index of this queue in pool
        final short mode;          // 0: lifo, > 0: fifo, < 0: shared
        volatile int qlock;        // 1: locked, -1: terminate; else 0
        volatile int base;         // index of next slot for poll
        int top;                   // index of next slot for push
        ForkJoinTask<?>[] array;   // the elements (initially unallocated)
        final ForkJoinPool pool;   // the containing pool (may be null)
        final ForkJoinWorkerThread owner; // owning thread or null if shared
        volatile Thread parker;    // == owner during call to park; else null
        volatile ForkJoinTask<?> currentJoin;  // task being joined in awaitJoin
        ForkJoinTask<?> currentSteal; // current non-local task being executed

        WorkQueue(ForkJoinPool pool, ForkJoinWorkerThread owner, int mode,
                  int seed) {
            this.pool = pool;
            this.owner = owner;
            this.mode = (short)mode;
            this.hint = seed; // store initial seed for runWorker
            base = top = INITIAL_QUEUE_CAPACITY >>> 1;
        }

        final int queueSize() {
            int n = base - top;       // non-owner callers must read base first
            return (n >= 0) ? 0 : -n; // ignore transient negative
        }

        final boolean isEmpty() {
            ForkJoinTask<?>[] a; int m, s;
            int n = base - (s = top);
            return (n >= 0 ||
                    (n == -1 &&
                     ((a = array) == null ||
                      (m = a.length - 1) < 0 ||
                      U.getObject
                      (a, (long)((m & (s - 1)) << ASHIFT) + ABASE) == null)));
        }

        final void push(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a; ForkJoinPool p;
            int s = top, n;
            if ((a = array) != null) {    // ignore if queue removed
                int m = a.length - 1;
                U.putOrderedObject(a, ((m & s) << ASHIFT) + ABASE, task);
                if ((n = (top = s + 1) - base) <= 2)
                    (p = pool).signalWork(p.workQueues, this);
                else if (n >= m)
                    growArray();
            }
        }

        final ForkJoinTask<?>[] growArray() {
            ForkJoinTask<?>[] oldA = array;
            int size = oldA != null ? oldA.length << 1 : INITIAL_QUEUE_CAPACITY;
            if (size > MAXIMUM_QUEUE_CAPACITY)
                throw new RejectedExecutionException("Queue capacity exceeded");
            int oldMask, t, b;
            ForkJoinTask<?>[] a = array = new ForkJoinTask<?>[size];
            if (oldA != null && (oldMask = oldA.length - 1) >= 0 &&
                (t = top) - (b = base) > 0) {
                int mask = size - 1;
                do {
                    ForkJoinTask<?> x;
                    int oldj = ((b & oldMask) << ASHIFT) + ABASE;
                    int j    = ((b &    mask) << ASHIFT) + ABASE;
                    x = (ForkJoinTask<?>)U.getObjectVolatile(oldA, oldj);
                    if (x != null &&
                        U.compareAndSwapObject(oldA, oldj, x, null))
                        U.putObjectVolatile(a, j, x);
                } while (++b != t);
            }
            return a;
        }

        final ForkJoinTask<?> pop() {
            ForkJoinTask<?>[] a; ForkJoinTask<?> t; int m;
            if ((a = array) != null && (m = a.length - 1) >= 0) {
                for (int s; (s = top - 1) - base >= 0;) {
                    long j = ((m & s) << ASHIFT) + ABASE;
                    if ((t = (ForkJoinTask<?>)U.getObject(a, j)) == null)
                        break;
                    if (U.compareAndSwapObject(a, j, t, null)) {
                        top = s;
                        return t;
                    }
                }
            }
            return null;
        }

        final ForkJoinTask<?> pollAt(int b) {
            ForkJoinTask<?> t; ForkJoinTask<?>[] a;
            if ((a = array) != null) {
                int j = (((a.length - 1) & b) << ASHIFT) + ABASE;
                if ((t = (ForkJoinTask<?>)U.getObjectVolatile(a, j)) != null &&
                    base == b && U.compareAndSwapObject(a, j, t, null)) {
                    U.putOrderedInt(this, QBASE, b + 1);
                    return t;
                }
            }
            return null;
        }

        final ForkJoinTask<?> poll() {
            ForkJoinTask<?>[] a; int b; ForkJoinTask<?> t;
            while ((b = base) - top < 0 && (a = array) != null) {
                int j = (((a.length - 1) & b) << ASHIFT) + ABASE;
                t = (ForkJoinTask<?>)U.getObjectVolatile(a, j);
                if (t != null) {
                    if (U.compareAndSwapObject(a, j, t, null)) {
                        U.putOrderedInt(this, QBASE, b + 1);
                        return t;
                    }
                }
                else if (base == b) {
                    if (b + 1 == top)
                        break;
                    Thread.yield(); // wait for lagging update (very rare)
                }
            }
            return null;
        }

        final ForkJoinTask<?> nextLocalTask() {
            return mode == 0 ? pop() : poll();
        }

        final ForkJoinTask<?> peek() {
            ForkJoinTask<?>[] a = array; int m;
            if (a == null || (m = a.length - 1) < 0)
                return null;
            int i = mode == 0 ? top - 1 : base;
            int j = ((i & m) << ASHIFT) + ABASE;
            return (ForkJoinTask<?>)U.getObjectVolatile(a, j);
        }

        final boolean tryUnpush(ForkJoinTask<?> t) {
            ForkJoinTask<?>[] a; int s;
            if ((a = array) != null && (s = top) != base &&
                U.compareAndSwapObject
                (a, (((a.length - 1) & --s) << ASHIFT) + ABASE, t, null)) {
                top = s;
                return true;
            }
            return false;
        }

        final void cancelAll() {
            ForkJoinTask.cancelIgnoringExceptions(currentJoin);
            ForkJoinTask.cancelIgnoringExceptions(currentSteal);
            for (ForkJoinTask<?> t; (t = poll()) != null; )
                ForkJoinTask.cancelIgnoringExceptions(t);
        }


        final void pollAndExecAll() {
            for (ForkJoinTask<?> t; (t = poll()) != null;)
                t.doExec();
        }

        final void runTask(ForkJoinTask<?> task) {
            if ((currentSteal = task) != null) {
                ForkJoinWorkerThread thread;
                task.doExec();
                ForkJoinTask<?>[] a = array;
                int md = mode;
                ++nsteals;
                currentSteal = null;
                if (md != 0)
                    pollAndExecAll();
                else if (a != null) {
                    int s, m = a.length - 1;
                    ForkJoinTask<?> t;
                    while ((s = top - 1) - base >= 0 &&
                           (t = (ForkJoinTask<?>)U.getAndSetObject
                            (a, ((m & s) << ASHIFT) + ABASE, null)) != null) {
                        top = s;
                        t.doExec();
                    }
                }
                if ((thread = owner) != null) // no need to do in finally clause
                    thread.afterTopLevelExec();
            }
        }

        final boolean tryRemoveAndExec(ForkJoinTask<?> task) {
            boolean stat;
            ForkJoinTask<?>[] a; int m, s, b, n;
            if (task != null && (a = array) != null && (m = a.length - 1) >= 0 &&
                (n = (s = top) - (b = base)) > 0) {
                boolean removed = false, empty = true;
                stat = true;
                for (ForkJoinTask<?> t;;) {           // traverse from s to b
                    long j = ((--s & m) << ASHIFT) + ABASE;
                    t = (ForkJoinTask<?>)U.getObject(a, j);
                    if (t == null)                    // inconsistent length
                        break;
                    else if (t == task) {
                        if (s + 1 == top) {           // pop
                            if (!U.compareAndSwapObject(a, j, task, null))
                                break;
                            top = s;
                            removed = true;
                        }
                        else if (base == b)           // replace with proxy
                            removed = U.compareAndSwapObject(a, j, task,
                                                             new EmptyTask());
                        break;
                    }
                    else if (t.status >= 0)
                        empty = false;
                    else if (s + 1 == top) {          // pop and throw away
                        if (U.compareAndSwapObject(a, j, t, null))
                            top = s;
                        break;
                    }
                    if (--n == 0) {
                        if (!empty && base == b)
                            stat = false;
                        break;
                    }
                }
                if (removed)
                    task.doExec();
            }
            else
                stat = false;
            return stat;
        }

        final boolean pollAndExecCC(CountedCompleter<?> root) {
            ForkJoinTask<?>[] a; int b; Object o; CountedCompleter<?> t, r;
            if ((b = base) - top < 0 && (a = array) != null) {
                long j = (((a.length - 1) & b) << ASHIFT) + ABASE;
                if ((o = U.getObjectVolatile(a, j)) == null)
                    return true; // retry
                if (o instanceof CountedCompleter) {
                    for (t = (CountedCompleter<?>)o, r = t;;) {
                        if (r == root) {
                            if (base == b &&
                                U.compareAndSwapObject(a, j, t, null)) {
                                U.putOrderedInt(this, QBASE, b + 1);
                                t.doExec();
                            }
                            return true;
                        }
                        else if ((r = r.completer) == null)
                            break; // not part of root computation
                    }
                }
            }
            return false;
        }

        final boolean externalPopAndExecCC(CountedCompleter<?> root) {
            ForkJoinTask<?>[] a; int s; Object o; CountedCompleter<?> t, r;
            if (base - (s = top) < 0 && (a = array) != null) {
                long j = (((a.length - 1) & (s - 1)) << ASHIFT) + ABASE;
                if ((o = U.getObject(a, j)) instanceof CountedCompleter) {
                    for (t = (CountedCompleter<?>)o, r = t;;) {
                        if (r == root) {
                            if (U.compareAndSwapInt(this, QLOCK, 0, 1)) {
                                if (top == s && array == a &&
                                    U.compareAndSwapObject(a, j, t, null)) {
                                    top = s - 1;
                                    qlock = 0;
                                    t.doExec();
                                }
                                else
                                    qlock = 0;
                            }
                            return true;
                        }
                        else if ((r = r.completer) == null)
                            break;
                    }
                }
            }
            return false;
        }

        final boolean internalPopAndExecCC(CountedCompleter<?> root) {
            ForkJoinTask<?>[] a; int s; Object o; CountedCompleter<?> t, r;
            if (base - (s = top) < 0 && (a = array) != null) {
                long j = (((a.length - 1) & (s - 1)) << ASHIFT) + ABASE;
                if ((o = U.getObject(a, j)) instanceof CountedCompleter) {
                    for (t = (CountedCompleter<?>)o, r = t;;) {
                        if (r == root) {
                            if (U.compareAndSwapObject(a, j, t, null)) {
                                top = s - 1;
                                t.doExec();
                            }
                            return true;
                        }
                        else if ((r = r.completer) == null)
                            break;
                    }
                }
            }
            return false;
        }

        final boolean isApparentlyUnblocked() {
            Thread wt; Thread.State s;
            return (eventCount >= 0 &&
                    (wt = owner) != null &&
                    (s = wt.getState()) != Thread.State.BLOCKED &&
                    s != Thread.State.WAITING &&
                    s != Thread.State.TIMED_WAITING);
        }

        private static final sun.misc.Unsafe U;
        private static final long QBASE;
        private static final long QLOCK;
        private static final int ABASE;
        private static final int ASHIFT;
        static {
            try {
                U = sun.misc.Unsafe.getUnsafe();
                Class<?> k = WorkQueue.class;
                Class<?> ak = ForkJoinTask[].class;
                QBASE = U.objectFieldOffset
                    (k.getDeclaredField("base"));
                QLOCK = U.objectFieldOffset
                    (k.getDeclaredField("qlock"));
                ABASE = U.arrayBaseOffset(ak);
                int scale = U.arrayIndexScale(ak);
                if ((scale & (scale - 1)) != 0)
                    throw new Error("data type scale not a power of two");
                ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }


    public static final ForkJoinWorkerThreadFactory
        defaultForkJoinWorkerThreadFactory;

    private static final RuntimePermission modifyThreadPermission;

    static final ForkJoinPool common;

    static final int commonParallelism;

    private static int poolNumberSequence;

    private static final synchronized int nextPoolId() {
        return ++poolNumberSequence;
    }


    private static final long IDLE_TIMEOUT      = 2000L * 1000L * 1000L; // 2sec

    private static final long FAST_IDLE_TIMEOUT =  200L * 1000L * 1000L;

    private static final long TIMEOUT_SLOP = 2000000L;

    private static final int MAX_HELP = 64;

    private static final int SEED_INCREMENT = 0x9e3779b9;


    private static final int  AC_SHIFT   = 48;
    private static final int  TC_SHIFT   = 32;
    private static final int  ST_SHIFT   = 31;
    private static final int  EC_SHIFT   = 16;

    private static final int  SMASK      = 0xffff;  // short bits
    private static final int  MAX_CAP    = 0x7fff;  // max #workers - 1
    private static final int  EVENMASK   = 0xfffe;  // even short bits
    private static final int  SQMASK     = 0x007e;  // max 64 (even) slots
    private static final int  SHORT_SIGN = 1 << 15;
    private static final int  INT_SIGN   = 1 << 31;

    private static final long STOP_BIT   = 0x0001L << ST_SHIFT;
    private static final long AC_MASK    = ((long)SMASK) << AC_SHIFT;
    private static final long TC_MASK    = ((long)SMASK) << TC_SHIFT;

    private static final long TC_UNIT    = 1L << TC_SHIFT;
    private static final long AC_UNIT    = 1L << AC_SHIFT;

    private static final int  UAC_SHIFT  = AC_SHIFT - 32;
    private static final int  UTC_SHIFT  = TC_SHIFT - 32;
    private static final int  UAC_MASK   = SMASK << UAC_SHIFT;
    private static final int  UTC_MASK   = SMASK << UTC_SHIFT;
    private static final int  UAC_UNIT   = 1 << UAC_SHIFT;
    private static final int  UTC_UNIT   = 1 << UTC_SHIFT;

    private static final int E_MASK      = 0x7fffffff; // no STOP_BIT
    private static final int E_SEQ       = 1 << EC_SHIFT;

    private static final int SHUTDOWN    = 1 << 31;
    private static final int PL_LOCK     = 2;
    private static final int PL_SIGNAL   = 1;
    private static final int PL_SPINS    = 1 << 8;

    static final int LIFO_QUEUE          =  0;
    static final int FIFO_QUEUE          =  1;
    static final int SHARED_QUEUE        = -1;

    volatile long stealCount;                  // collects worker counts
    volatile long ctl;                         // main pool control
    volatile int plock;                        // shutdown status and seqLock
    volatile int indexSeed;                    // worker/submitter index seed
    final short parallelism;                   // parallelism level
    final short mode;                          // LIFO/FIFO
    WorkQueue[] workQueues;                    // main registry
    final ForkJoinWorkerThreadFactory factory;
    final UncaughtExceptionHandler ueh;        // per-worker UEH
    final String workerNamePrefix;             // to create worker name string

    private int acquirePlock() {
        int spins = PL_SPINS, ps, nps;
        for (;;) {
            if (((ps = plock) & PL_LOCK) == 0 &&
                U.compareAndSwapInt(this, PLOCK, ps, nps = ps + PL_LOCK))
                return nps;
            else if (spins >= 0) {
                if (ThreadLocalRandom.nextSecondarySeed() >= 0)
                    --spins;
            }
            else if (U.compareAndSwapInt(this, PLOCK, ps, ps | PL_SIGNAL)) {
                synchronized (this) {
                    if ((plock & PL_SIGNAL) != 0) {
                        try {
                            wait();
                        } catch (InterruptedException ie) {
                            try {
                                Thread.currentThread().interrupt();
                            } catch (SecurityException ignore) {
                            }
                        }
                    }
                    else
                        notifyAll();
                }
            }
        }
    }

    private void releasePlock(int ps) {
        plock = ps;
        synchronized (this) { notifyAll(); }
    }

    private void tryAddWorker() {
        long c; int u, e;
        while ((u = (int)((c = ctl) >>> 32)) < 0 &&
               (u & SHORT_SIGN) != 0 && (e = (int)c) >= 0) {
            long nc = ((long)(((u + UTC_UNIT) & UTC_MASK) |
                              ((u + UAC_UNIT) & UAC_MASK)) << 32) | (long)e;
            if (U.compareAndSwapLong(this, CTL, c, nc)) {
                ForkJoinWorkerThreadFactory fac;
                Throwable ex = null;
                ForkJoinWorkerThread wt = null;
                try {
                    if ((fac = factory) != null &&
                        (wt = fac.newThread(this)) != null) {
                        wt.start();
                        break;
                    }
                } catch (Throwable rex) {
                    ex = rex;
                }
                deregisterWorker(wt, ex);
                break;
            }
        }
    }


    final WorkQueue registerWorker(ForkJoinWorkerThread wt) {
        UncaughtExceptionHandler handler; WorkQueue[] ws; int s, ps;
        wt.setDaemon(true);
        if ((handler = ueh) != null)
            wt.setUncaughtExceptionHandler(handler);
        do {} while (!U.compareAndSwapInt(this, INDEXSEED, s = indexSeed,
                                          s += SEED_INCREMENT) ||
                     s == 0); // skip 0
        WorkQueue w = new WorkQueue(this, wt, mode, s);
        if (((ps = plock) & PL_LOCK) != 0 ||
            !U.compareAndSwapInt(this, PLOCK, ps, ps += PL_LOCK))
            ps = acquirePlock();
        int nps = (ps & SHUTDOWN) | ((ps + PL_LOCK) & ~SHUTDOWN);
        try {
            if ((ws = workQueues) != null) {    // skip if shutting down
                int n = ws.length, m = n - 1;
                int r = (s << 1) | 1;           // use odd-numbered indices
                if (ws[r &= m] != null) {       // collision
                    int probes = 0;             // step by approx half size
                    int step = (n <= 4) ? 2 : ((n >>> 1) & EVENMASK) + 2;
                    while (ws[r = (r + step) & m] != null) {
                        if (++probes >= n) {
                            workQueues = ws = Arrays.copyOf(ws, n <<= 1);
                            m = n - 1;
                            probes = 0;
                        }
                    }
                }
                w.poolIndex = (short)r;
                w.eventCount = r; // volatile write orders
                ws[r] = w;
            }
        } finally {
            if (!U.compareAndSwapInt(this, PLOCK, ps, nps))
                releasePlock(nps);
        }
        wt.setName(workerNamePrefix.concat(Integer.toString(w.poolIndex >>> 1)));
        return w;
    }

    final void deregisterWorker(ForkJoinWorkerThread wt, Throwable ex) {
        WorkQueue w = null;
        if (wt != null && (w = wt.workQueue) != null) {
            int ps;
            w.qlock = -1;                // ensure set
            U.getAndAddLong(this, STEALCOUNT, w.nsteals); // collect steals
            if (((ps = plock) & PL_LOCK) != 0 ||
                !U.compareAndSwapInt(this, PLOCK, ps, ps += PL_LOCK))
                ps = acquirePlock();
            int nps = (ps & SHUTDOWN) | ((ps + PL_LOCK) & ~SHUTDOWN);
            try {
                int idx = w.poolIndex;
                WorkQueue[] ws = workQueues;
                if (ws != null && idx >= 0 && idx < ws.length && ws[idx] == w)
                    ws[idx] = null;
            } finally {
                if (!U.compareAndSwapInt(this, PLOCK, ps, nps))
                    releasePlock(nps);
            }
        }

        long c;                          // adjust ctl counts
        do {} while (!U.compareAndSwapLong
                     (this, CTL, c = ctl, (((c - AC_UNIT) & AC_MASK) |
                                           ((c - TC_UNIT) & TC_MASK) |
                                           (c & ~(AC_MASK|TC_MASK)))));

        if (!tryTerminate(false, false) && w != null && w.array != null) {
            w.cancelAll();               // cancel remaining tasks
            WorkQueue[] ws; WorkQueue v; Thread p; int u, i, e;
            while ((u = (int)((c = ctl) >>> 32)) < 0 && (e = (int)c) >= 0) {
                if (e > 0) {             // activate or create replacement
                    if ((ws = workQueues) == null ||
                        (i = e & SMASK) >= ws.length ||
                        (v = ws[i]) == null)
                        break;
                    long nc = (((long)(v.nextWait & E_MASK)) |
                               ((long)(u + UAC_UNIT) << 32));
                    if (v.eventCount != (e | INT_SIGN))
                        break;
                    if (U.compareAndSwapLong(this, CTL, c, nc)) {
                        v.eventCount = (e + E_SEQ) & E_MASK;
                        if ((p = v.parker) != null)
                            U.unpark(p);
                        break;
                    }
                }
                else {
                    if ((short)u < 0)
                        tryAddWorker();
                    break;
                }
            }
        }
        if (ex == null)                     // help clean refs on way out
            ForkJoinTask.helpExpungeStaleExceptions();
        else                                // rethrow
            ForkJoinTask.rethrow(ex);
    }


    final void externalPush(ForkJoinTask<?> task) {
        WorkQueue q; int m, s, n, am; ForkJoinTask<?>[] a;
        int r = ThreadLocalRandom.getProbe();
        int ps = plock;
        WorkQueue[] ws = workQueues;
        if (ps > 0 && ws != null && (m = (ws.length - 1)) >= 0 &&
            (q = ws[m & r & SQMASK]) != null && r != 0 &&
            U.compareAndSwapInt(q, QLOCK, 0, 1)) { // lock
            if ((a = q.array) != null &&
                (am = a.length - 1) > (n = (s = q.top) - q.base)) {
                int j = ((am & s) << ASHIFT) + ABASE;
                U.putOrderedObject(a, j, task);
                q.top = s + 1;                     // push on to deque
                q.qlock = 0;
                if (n <= 1)
                    signalWork(ws, q);
                return;
            }
            q.qlock = 0;
        }
        fullExternalPush(task);
    }

    private void fullExternalPush(ForkJoinTask<?> task) {
        int r;
        if ((r = ThreadLocalRandom.getProbe()) == 0) {
            ThreadLocalRandom.localInit();
            r = ThreadLocalRandom.getProbe();
        }
        for (;;) {
            WorkQueue[] ws; WorkQueue q; int ps, m, k;
            boolean move = false;
            if ((ps = plock) < 0)
                throw new RejectedExecutionException();
            else if (ps == 0 || (ws = workQueues) == null ||
                     (m = ws.length - 1) < 0) { // initialize workQueues
                int p = parallelism;            // find power of two table size
                int n = (p > 1) ? p - 1 : 1;    // ensure at least 2 slots
                n |= n >>> 1; n |= n >>> 2;  n |= n >>> 4;
                n |= n >>> 8; n |= n >>> 16; n = (n + 1) << 1;
                WorkQueue[] nws = ((ws = workQueues) == null || ws.length == 0 ?
                                   new WorkQueue[n] : null);
                if (((ps = plock) & PL_LOCK) != 0 ||
                    !U.compareAndSwapInt(this, PLOCK, ps, ps += PL_LOCK))
                    ps = acquirePlock();
                if (((ws = workQueues) == null || ws.length == 0) && nws != null)
                    workQueues = nws;
                int nps = (ps & SHUTDOWN) | ((ps + PL_LOCK) & ~SHUTDOWN);
                if (!U.compareAndSwapInt(this, PLOCK, ps, nps))
                    releasePlock(nps);
            }
            else if ((q = ws[k = r & m & SQMASK]) != null) {
                if (q.qlock == 0 && U.compareAndSwapInt(q, QLOCK, 0, 1)) {
                    ForkJoinTask<?>[] a = q.array;
                    int s = q.top;
                    boolean submitted = false;
                    try {                      // locked version of push
                        if ((a != null && a.length > s + 1 - q.base) ||
                            (a = q.growArray()) != null) {   // must presize
                            int j = (((a.length - 1) & s) << ASHIFT) + ABASE;
                            U.putOrderedObject(a, j, task);
                            q.top = s + 1;
                            submitted = true;
                        }
                    } finally {
                        q.qlock = 0;  // unlock
                    }
                    if (submitted) {
                        signalWork(ws, q);
                        return;
                    }
                }
                move = true; // move on failure
            }
            else if (((ps = plock) & PL_LOCK) == 0) { // create new queue
                q = new WorkQueue(this, null, SHARED_QUEUE, r);
                q.poolIndex = (short)k;
                if (((ps = plock) & PL_LOCK) != 0 ||
                    !U.compareAndSwapInt(this, PLOCK, ps, ps += PL_LOCK))
                    ps = acquirePlock();
                if ((ws = workQueues) != null && k < ws.length && ws[k] == null)
                    ws[k] = q;
                int nps = (ps & SHUTDOWN) | ((ps + PL_LOCK) & ~SHUTDOWN);
                if (!U.compareAndSwapInt(this, PLOCK, ps, nps))
                    releasePlock(nps);
            }
            else
                move = true; // move if busy
            if (move)
                r = ThreadLocalRandom.advanceProbe(r);
        }
    }


    final void incrementActiveCount() {
        long c;
        do {} while (!U.compareAndSwapLong
                     (this, CTL, c = ctl, ((c & ~AC_MASK) |
                                           ((c & AC_MASK) + AC_UNIT))));
    }

    final void signalWork(WorkQueue[] ws, WorkQueue q) {
        for (;;) {
            long c; int e, u, i; WorkQueue w; Thread p;
            if ((u = (int)((c = ctl) >>> 32)) >= 0)
                break;
            if ((e = (int)c) <= 0) {
                if ((short)u < 0)
                    tryAddWorker();
                break;
            }
            if (ws == null || ws.length <= (i = e & SMASK) ||
                (w = ws[i]) == null)
                break;
            long nc = (((long)(w.nextWait & E_MASK)) |
                       ((long)(u + UAC_UNIT)) << 32);
            int ne = (e + E_SEQ) & E_MASK;
            if (w.eventCount == (e | INT_SIGN) &&
                U.compareAndSwapLong(this, CTL, c, nc)) {
                w.eventCount = ne;
                if ((p = w.parker) != null)
                    U.unpark(p);
                break;
            }
            if (q != null && q.base >= q.top)
                break;
        }
    }


    final void runWorker(WorkQueue w) {
        w.growArray(); // allocate queue
        for (int r = w.hint; scan(w, r) == 0; ) {
            r ^= r << 13; r ^= r >>> 17; r ^= r << 5; // xorshift
        }
    }

    private final int scan(WorkQueue w, int r) {
        WorkQueue[] ws; int m;
        long c = ctl;                            // for consistency check
        if ((ws = workQueues) != null && (m = ws.length - 1) >= 0 && w != null) {
            for (int j = m + m + 1, ec = w.eventCount;;) {
                WorkQueue q; int b, e; ForkJoinTask<?>[] a; ForkJoinTask<?> t;
                if ((q = ws[(r - j) & m]) != null &&
                    (b = q.base) - q.top < 0 && (a = q.array) != null) {
                    long i = (((a.length - 1) & b) << ASHIFT) + ABASE;
                    if ((t = ((ForkJoinTask<?>)
                              U.getObjectVolatile(a, i))) != null) {
                        if (ec < 0)
                            helpRelease(c, ws, w, q, b);
                        else if (q.base == b &&
                                 U.compareAndSwapObject(a, i, t, null)) {
                            U.putOrderedInt(q, QBASE, b + 1);
                            if ((b + 1) - q.top < 0)
                                signalWork(ws, q);
                            w.runTask(t);
                        }
                    }
                    break;
                }
                else if (--j < 0) {
                    if ((ec | (e = (int)c)) < 0) // inactive or terminating
                        return awaitWork(w, c, ec);
                    else if (ctl == c) {         // try to inactivate and enqueue
                        long nc = (long)ec | ((c - AC_UNIT) & (AC_MASK|TC_MASK));
                        w.nextWait = e;
                        w.eventCount = ec | INT_SIGN;
                        if (!U.compareAndSwapLong(this, CTL, c, nc))
                            w.eventCount = ec;   // back out
                    }
                    break;
                }
            }
        }
        return 0;
    }

    private final int awaitWork(WorkQueue w, long c, int ec) {
        int stat, ns; long parkTime, deadline;
        if ((stat = w.qlock) >= 0 && w.eventCount == ec && ctl == c &&
            !Thread.interrupted()) {
            int e = (int)c;
            int u = (int)(c >>> 32);
            int d = (u >> UAC_SHIFT) + parallelism; // active count

            if (e < 0 || (d <= 0 && tryTerminate(false, false)))
                stat = w.qlock = -1;          // pool is terminating
            else if ((ns = w.nsteals) != 0) { // collect steals and retry
                w.nsteals = 0;
                U.getAndAddLong(this, STEALCOUNT, (long)ns);
            }
            else {
                long pc = ((d > 0 || ec != (e | INT_SIGN)) ? 0L :
                           ((long)(w.nextWait & E_MASK)) | // ctl to restore
                           ((long)(u + UAC_UNIT)) << 32);
                if (pc != 0L) {               // timed wait if last waiter
                    int dc = -(short)(c >>> TC_SHIFT);
                    parkTime = (dc < 0 ? FAST_IDLE_TIMEOUT:
                                (dc + 1) * IDLE_TIMEOUT);
                    deadline = System.nanoTime() + parkTime - TIMEOUT_SLOP;
                }
                else
                    parkTime = deadline = 0L;
                if (w.eventCount == ec && ctl == c) {
                    Thread wt = Thread.currentThread();
                    U.putObject(wt, PARKBLOCKER, this);
                    w.parker = wt;            // emulate LockSupport.park
                    if (w.eventCount == ec && ctl == c)
                        U.park(false, parkTime);  // must recheck before park
                    w.parker = null;
                    U.putObject(wt, PARKBLOCKER, null);
                    if (parkTime != 0L && ctl == c &&
                        deadline - System.nanoTime() <= 0L &&
                        U.compareAndSwapLong(this, CTL, c, pc))
                        stat = w.qlock = -1;  // shrink pool
                }
            }
        }
        return stat;
    }

    private final void helpRelease(long c, WorkQueue[] ws, WorkQueue w,
                                   WorkQueue q, int b) {
        WorkQueue v; int e, i; Thread p;
        if (w != null && w.eventCount < 0 && (e = (int)c) > 0 &&
            ws != null && ws.length > (i = e & SMASK) &&
            (v = ws[i]) != null && ctl == c) {
            long nc = (((long)(v.nextWait & E_MASK)) |
                       ((long)((int)(c >>> 32) + UAC_UNIT)) << 32);
            int ne = (e + E_SEQ) & E_MASK;
            if (q != null && q.base == b && w.eventCount < 0 &&
                v.eventCount == (e | INT_SIGN) &&
                U.compareAndSwapLong(this, CTL, c, nc)) {
                v.eventCount = ne;
                if ((p = v.parker) != null)
                    U.unpark(p);
            }
        }
    }

    private int tryHelpStealer(WorkQueue joiner, ForkJoinTask<?> task) {
        int stat = 0, steps = 0;                    // bound to avoid cycles
        if (task != null && joiner != null &&
            joiner.base - joiner.top >= 0) {        // hoist checks
            restart: for (;;) {
                ForkJoinTask<?> subtask = task;     // current target
                for (WorkQueue j = joiner, v;;) {   // v is stealer of subtask
                    WorkQueue[] ws; int m, s, h;
                    if ((s = task.status) < 0) {
                        stat = s;
                        break restart;
                    }
                    if ((ws = workQueues) == null || (m = ws.length - 1) <= 0)
                        break restart;              // shutting down
                    if ((v = ws[h = (j.hint | 1) & m]) == null ||
                        v.currentSteal != subtask) {
                        for (int origin = h;;) {    // find stealer
                            if (((h = (h + 2) & m) & 15) == 1 &&
                                (subtask.status < 0 || j.currentJoin != subtask))
                                continue restart;   // occasional staleness check
                            if ((v = ws[h]) != null &&
                                v.currentSteal == subtask) {
                                j.hint = h;        // save hint
                                break;
                            }
                            if (h == origin)
                                break restart;      // cannot find stealer
                        }
                    }
                    for (;;) { // help stealer or descend to its stealer
                        ForkJoinTask<?>[] a; int b;
                        if (subtask.status < 0)     // surround probes with
                            continue restart;       //   consistency checks
                        if ((b = v.base) - v.top < 0 && (a = v.array) != null) {
                            int i = (((a.length - 1) & b) << ASHIFT) + ABASE;
                            ForkJoinTask<?> t =
                                (ForkJoinTask<?>)U.getObjectVolatile(a, i);
                            if (subtask.status < 0 || j.currentJoin != subtask ||
                                v.currentSteal != subtask)
                                continue restart;   // stale
                            stat = 1;               // apparent progress
                            if (v.base == b) {
                                if (t == null)
                                    break restart;
                                if (U.compareAndSwapObject(a, i, t, null)) {
                                    U.putOrderedInt(v, QBASE, b + 1);
                                    ForkJoinTask<?> ps = joiner.currentSteal;
                                    int jt = joiner.top;
                                    do {
                                        joiner.currentSteal = t;
                                        t.doExec(); // clear local tasks too
                                    } while (task.status >= 0 &&
                                             joiner.top != jt &&
                                             (t = joiner.pop()) != null);
                                    joiner.currentSteal = ps;
                                    break restart;
                                }
                            }
                        }
                        else {                      // empty -- try to descend
                            ForkJoinTask<?> next = v.currentJoin;
                            if (subtask.status < 0 || j.currentJoin != subtask ||
                                v.currentSteal != subtask)
                                continue restart;   // stale
                            else if (next == null || ++steps == MAX_HELP)
                                break restart;      // dead-end or maybe cyclic
                            else {
                                subtask = next;
                                j = v;
                                break;
                            }
                        }
                    }
                }
            }
        }
        return stat;
    }

    final int helpComplete(WorkQueue joiner, CountedCompleter<?> task,
                           int maxTasks) {
        WorkQueue[] ws; int m;
        int s = 0;
        if ((ws = workQueues) != null && (m = ws.length - 1) >= 0 &&
            joiner != null && task != null) {
            int j = joiner.poolIndex;
            int scans = m + m + 1;
            long c = 0L;              // for stability check
            for (int k = scans; ; j += 2) {
                WorkQueue q;
                if ((s = task.status) < 0)
                    break;
                else if (joiner.internalPopAndExecCC(task)) {
                    if (--maxTasks <= 0) {
                        s = task.status;
                        break;
                    }
                    k = scans;
                }
                else if ((s = task.status) < 0)
                    break;
                else if ((q = ws[j & m]) != null && q.pollAndExecCC(task)) {
                    if (--maxTasks <= 0) {
                        s = task.status;
                        break;
                    }
                    k = scans;
                }
                else if (--k < 0) {
                    if (c == (c = ctl))
                        break;
                    k = scans;
                }
            }
        }
        return s;
    }

    final boolean tryCompensate(long c) {
        WorkQueue[] ws = workQueues;
        int pc = parallelism, e = (int)c, m, tc;
        if (ws != null && (m = ws.length - 1) >= 0 && e >= 0 && ctl == c) {
            WorkQueue w = ws[e & m];
            if (e != 0 && w != null) {
                Thread p;
                long nc = ((long)(w.nextWait & E_MASK) |
                           (c & (AC_MASK|TC_MASK)));
                int ne = (e + E_SEQ) & E_MASK;
                if (w.eventCount == (e | INT_SIGN) &&
                    U.compareAndSwapLong(this, CTL, c, nc)) {
                    w.eventCount = ne;
                    if ((p = w.parker) != null)
                        U.unpark(p);
                    return true;   // replace with idle worker
                }
            }
            else if ((tc = (short)(c >>> TC_SHIFT)) >= 0 &&
                     (int)(c >> AC_SHIFT) + pc > 1) {
                long nc = ((c - AC_UNIT) & AC_MASK) | (c & ~AC_MASK);
                if (U.compareAndSwapLong(this, CTL, c, nc))
                    return true;   // no compensation
            }
            else if (tc + pc < MAX_CAP) {
                long nc = ((c + TC_UNIT) & TC_MASK) | (c & ~TC_MASK);
                if (U.compareAndSwapLong(this, CTL, c, nc)) {
                    ForkJoinWorkerThreadFactory fac;
                    Throwable ex = null;
                    ForkJoinWorkerThread wt = null;
                    try {
                        if ((fac = factory) != null &&
                            (wt = fac.newThread(this)) != null) {
                            wt.start();
                            return true;
                        }
                    } catch (Throwable rex) {
                        ex = rex;
                    }
                    deregisterWorker(wt, ex); // clean up and return false
                }
            }
        }
        return false;
    }

    final int awaitJoin(WorkQueue joiner, ForkJoinTask<?> task) {
        int s = 0;
        if (task != null && (s = task.status) >= 0 && joiner != null) {
            ForkJoinTask<?> prevJoin = joiner.currentJoin;
            joiner.currentJoin = task;
            do {} while (joiner.tryRemoveAndExec(task) && // process local tasks
                         (s = task.status) >= 0);
            if (s >= 0 && (task instanceof CountedCompleter))
                s = helpComplete(joiner, (CountedCompleter<?>)task, Integer.MAX_VALUE);
            long cc = 0;        // for stability checks
            while (s >= 0 && (s = task.status) >= 0) {
                if ((s = tryHelpStealer(joiner, task)) == 0 &&
                    (s = task.status) >= 0) {
                    if (!tryCompensate(cc))
                        cc = ctl;
                    else {
                        if (task.trySetSignal() && (s = task.status) >= 0) {
                            synchronized (task) {
                                if (task.status >= 0) {
                                    try {                // see ForkJoinTask
                                        task.wait();     //  for explanation
                                    } catch (InterruptedException ie) {
                                    }
                                }
                                else
                                    task.notifyAll();
                            }
                        }
                        long c; // reactivate
                        do {} while (!U.compareAndSwapLong
                                     (this, CTL, c = ctl,
                                      ((c & ~AC_MASK) |
                                       ((c & AC_MASK) + AC_UNIT))));
                    }
                }
            }
            joiner.currentJoin = prevJoin;
        }
        return s;
    }

    final void helpJoinOnce(WorkQueue joiner, ForkJoinTask<?> task) {
        int s;
        if (joiner != null && task != null && (s = task.status) >= 0) {
            ForkJoinTask<?> prevJoin = joiner.currentJoin;
            joiner.currentJoin = task;
            do {} while (joiner.tryRemoveAndExec(task) && // process local tasks
                         (s = task.status) >= 0);
            if (s >= 0) {
                if (task instanceof CountedCompleter)
                    helpComplete(joiner, (CountedCompleter<?>)task, Integer.MAX_VALUE);
                do {} while (task.status >= 0 &&
                             tryHelpStealer(joiner, task) > 0);
            }
            joiner.currentJoin = prevJoin;
        }
    }

    private WorkQueue findNonEmptyStealQueue() {
        int r = ThreadLocalRandom.nextSecondarySeed();
        for (;;) {
            int ps = plock, m; WorkQueue[] ws; WorkQueue q;
            if ((ws = workQueues) != null && (m = ws.length - 1) >= 0) {
                for (int j = (m + 1) << 2; j >= 0; --j) {
                    if ((q = ws[(((r - j) << 1) | 1) & m]) != null &&
                        q.base - q.top < 0)
                        return q;
                }
            }
            if (plock == ps)
                return null;
        }
    }

    final void helpQuiescePool(WorkQueue w) {
        ForkJoinTask<?> ps = w.currentSteal;
        for (boolean active = true;;) {
            long c; WorkQueue q; ForkJoinTask<?> t; int b;
            while ((t = w.nextLocalTask()) != null)
                t.doExec();
            if ((q = findNonEmptyStealQueue()) != null) {
                if (!active) {      // re-establish active count
                    active = true;
                    do {} while (!U.compareAndSwapLong
                                 (this, CTL, c = ctl,
                                  ((c & ~AC_MASK) |
                                   ((c & AC_MASK) + AC_UNIT))));
                }
                if ((b = q.base) - q.top < 0 && (t = q.pollAt(b)) != null)
                    w.runTask(t);
            }
            else if (active) {      // decrement active count without queuing
                long nc = ((c = ctl) & ~AC_MASK) | ((c & AC_MASK) - AC_UNIT);
                if ((int)(nc >> AC_SHIFT) + parallelism == 0)
                    break;          // bypass decrement-then-increment
                if (U.compareAndSwapLong(this, CTL, c, nc))
                    active = false;
            }
            else if ((int)((c = ctl) >> AC_SHIFT) + parallelism <= 0 &&
                     U.compareAndSwapLong
                     (this, CTL, c, ((c & ~AC_MASK) |
                                     ((c & AC_MASK) + AC_UNIT))))
                break;
        }
    }

    final ForkJoinTask<?> nextTaskFor(WorkQueue w) {
        for (ForkJoinTask<?> t;;) {
            WorkQueue q; int b;
            if ((t = w.nextLocalTask()) != null)
                return t;
            if ((q = findNonEmptyStealQueue()) == null)
                return null;
            if ((b = q.base) - q.top < 0 && (t = q.pollAt(b)) != null)
                return t;
        }
    }

    static int getSurplusQueuedTaskCount() {
        Thread t; ForkJoinWorkerThread wt; ForkJoinPool pool; WorkQueue q;
        if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)) {
            int p = (pool = (wt = (ForkJoinWorkerThread)t).pool).parallelism;
            int n = (q = wt.workQueue).top - q.base;
            int a = (int)(pool.ctl >> AC_SHIFT) + p;
            return n - (a > (p >>>= 1) ? 0 :
                        a > (p >>>= 1) ? 1 :
                        a > (p >>>= 1) ? 2 :
                        a > (p >>>= 1) ? 4 :
                        8);
        }
        return 0;
    }


    private boolean tryTerminate(boolean now, boolean enable) {
        int ps;
        if (this == common)                        // cannot shut down
            return false;
        if ((ps = plock) >= 0) {                   // enable by setting plock
            if (!enable)
                return false;
            if ((ps & PL_LOCK) != 0 ||
                !U.compareAndSwapInt(this, PLOCK, ps, ps += PL_LOCK))
                ps = acquirePlock();
            int nps = ((ps + PL_LOCK) & ~SHUTDOWN) | SHUTDOWN;
            if (!U.compareAndSwapInt(this, PLOCK, ps, nps))
                releasePlock(nps);
        }
        for (long c;;) {
            if (((c = ctl) & STOP_BIT) != 0) {     // already terminating
                if ((short)(c >>> TC_SHIFT) + parallelism <= 0) {
                    synchronized (this) {
                        notifyAll();               // signal when 0 workers
                    }
                }
                return true;
            }
            if (!now) {                            // check if idle & no tasks
                WorkQueue[] ws; WorkQueue w;
                if ((int)(c >> AC_SHIFT) + parallelism > 0)
                    return false;
                if ((ws = workQueues) != null) {
                    for (int i = 0; i < ws.length; ++i) {
                        if ((w = ws[i]) != null &&
                            (!w.isEmpty() ||
                             ((i & 1) != 0 && w.eventCount >= 0))) {
                            signalWork(ws, w);
                            return false;
                        }
                    }
                }
            }
            if (U.compareAndSwapLong(this, CTL, c, c | STOP_BIT)) {
                for (int pass = 0; pass < 3; ++pass) {
                    WorkQueue[] ws; WorkQueue w; Thread wt;
                    if ((ws = workQueues) != null) {
                        int n = ws.length;
                        for (int i = 0; i < n; ++i) {
                            if ((w = ws[i]) != null) {
                                w.qlock = -1;
                                if (pass > 0) {
                                    w.cancelAll();
                                    if (pass > 1 && (wt = w.owner) != null) {
                                        if (!wt.isInterrupted()) {
                                            try {
                                                wt.interrupt();
                                            } catch (Throwable ignore) {
                                            }
                                        }
                                        U.unpark(wt);
                                    }
                                }
                            }
                        }
                        int i, e; long cc; Thread p;
                        while ((e = (int)(cc = ctl) & E_MASK) != 0 &&
                               (i = e & SMASK) < n && i >= 0 &&
                               (w = ws[i]) != null) {
                            long nc = ((long)(w.nextWait & E_MASK) |
                                       ((cc + AC_UNIT) & AC_MASK) |
                                       (cc & (TC_MASK|STOP_BIT)));
                            if (w.eventCount == (e | INT_SIGN) &&
                                U.compareAndSwapLong(this, CTL, cc, nc)) {
                                w.eventCount = (e + E_SEQ) & E_MASK;
                                w.qlock = -1;
                                if ((p = w.parker) != null)
                                    U.unpark(p);
                            }
                        }
                    }
                }
            }
        }
    }


    static WorkQueue commonSubmitterQueue() {
        ForkJoinPool p; WorkQueue[] ws; int m, z;
        return ((z = ThreadLocalRandom.getProbe()) != 0 &&
                (p = common) != null &&
                (ws = p.workQueues) != null &&
                (m = ws.length - 1) >= 0) ?
            ws[m & z & SQMASK] : null;
    }

    final boolean tryExternalUnpush(ForkJoinTask<?> task) {
        WorkQueue joiner; ForkJoinTask<?>[] a; int m, s;
        WorkQueue[] ws = workQueues;
        int z = ThreadLocalRandom.getProbe();
        boolean popped = false;
        if (ws != null && (m = ws.length - 1) >= 0 &&
            (joiner = ws[z & m & SQMASK]) != null &&
            joiner.base != (s = joiner.top) &&
            (a = joiner.array) != null) {
            long j = (((a.length - 1) & (s - 1)) << ASHIFT) + ABASE;
            if (U.getObject(a, j) == task &&
                U.compareAndSwapInt(joiner, QLOCK, 0, 1)) {
                if (joiner.top == s && joiner.array == a &&
                    U.compareAndSwapObject(a, j, task, null)) {
                    joiner.top = s - 1;
                    popped = true;
                }
                joiner.qlock = 0;
            }
        }
        return popped;
    }

    final int externalHelpComplete(CountedCompleter<?> task, int maxTasks) {
        WorkQueue joiner; int m;
        WorkQueue[] ws = workQueues;
        int j = ThreadLocalRandom.getProbe();
        int s = 0;
        if (ws != null && (m = ws.length - 1) >= 0 &&
            (joiner = ws[j & m & SQMASK]) != null && task != null) {
            int scans = m + m + 1;
            long c = 0L;             // for stability check
            j |= 1;                  // poll odd queues
            for (int k = scans; ; j += 2) {
                WorkQueue q;
                if ((s = task.status) < 0)
                    break;
                else if (joiner.externalPopAndExecCC(task)) {
                    if (--maxTasks <= 0) {
                        s = task.status;
                        break;
                    }
                    k = scans;
                }
                else if ((s = task.status) < 0)
                    break;
                else if ((q = ws[j & m]) != null && q.pollAndExecCC(task)) {
                    if (--maxTasks <= 0) {
                        s = task.status;
                        break;
                    }
                    k = scans;
                }
                else if (--k < 0) {
                    if (c == (c = ctl))
                        break;
                    k = scans;
                }
            }
        }
        return s;
    }



    public ForkJoinPool() {
        this(Math.min(MAX_CAP, Runtime.getRuntime().availableProcessors()),
             defaultForkJoinWorkerThreadFactory, null, false);
    }

    public ForkJoinPool(int parallelism) {
        this(parallelism, defaultForkJoinWorkerThreadFactory, null, false);
    }

    public ForkJoinPool(int parallelism,
                        ForkJoinWorkerThreadFactory factory,
                        UncaughtExceptionHandler handler,
                        boolean asyncMode) {
        this(checkParallelism(parallelism),
             checkFactory(factory),
             handler,
             (asyncMode ? FIFO_QUEUE : LIFO_QUEUE),
             "ForkJoinPool-" + nextPoolId() + "-worker-");
        checkPermission();
    }

    private static int checkParallelism(int parallelism) {
        if (parallelism <= 0 || parallelism > MAX_CAP)
            throw new IllegalArgumentException();
        return parallelism;
    }

    private static ForkJoinWorkerThreadFactory checkFactory
        (ForkJoinWorkerThreadFactory factory) {
        if (factory == null)
            throw new NullPointerException();
        return factory;
    }

    private ForkJoinPool(int parallelism,
                         ForkJoinWorkerThreadFactory factory,
                         UncaughtExceptionHandler handler,
                         int mode,
                         String workerNamePrefix) {
        this.workerNamePrefix = workerNamePrefix;
        this.factory = factory;
        this.ueh = handler;
        this.mode = (short)mode;
        this.parallelism = (short)parallelism;
        long np = (long)(-parallelism); // offset ctl counts
        this.ctl = ((np << AC_SHIFT) & AC_MASK) | ((np << TC_SHIFT) & TC_MASK);
    }

    public static ForkJoinPool commonPool() {
        return common;
    }


    public <T> T invoke(ForkJoinTask<T> task) {
        if (task == null)
            throw new NullPointerException();
        externalPush(task);
        return task.join();
    }

    public void execute(ForkJoinTask<?> task) {
        if (task == null)
            throw new NullPointerException();
        externalPush(task);
    }


    public void execute(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<?> job;
        if (task instanceof ForkJoinTask<?>) // avoid re-wrap
            job = (ForkJoinTask<?>) task;
        else
            job = new ForkJoinTask.RunnableExecuteAction(task);
        externalPush(job);
    }

    public <T> ForkJoinTask<T> submit(ForkJoinTask<T> task) {
        if (task == null)
            throw new NullPointerException();
        externalPush(task);
        return task;
    }

    public <T> ForkJoinTask<T> submit(Callable<T> task) {
        ForkJoinTask<T> job = new ForkJoinTask.AdaptedCallable<T>(task);
        externalPush(job);
        return job;
    }

    public <T> ForkJoinTask<T> submit(Runnable task, T result) {
        ForkJoinTask<T> job = new ForkJoinTask.AdaptedRunnable<T>(task, result);
        externalPush(job);
        return job;
    }

    public ForkJoinTask<?> submit(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<?> job;
        if (task instanceof ForkJoinTask<?>) // avoid re-wrap
            job = (ForkJoinTask<?>) task;
        else
            job = new ForkJoinTask.AdaptedRunnableAction(task);
        externalPush(job);
        return job;
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        ArrayList<Future<T>> futures = new ArrayList<Future<T>>(tasks.size());

        boolean done = false;
        try {
            for (Callable<T> t : tasks) {
                ForkJoinTask<T> f = new ForkJoinTask.AdaptedCallable<T>(t);
                futures.add(f);
                externalPush(f);
            }
            for (int i = 0, size = futures.size(); i < size; i++)
                ((ForkJoinTask<?>)futures.get(i)).quietlyJoin();
            done = true;
            return futures;
        } finally {
            if (!done)
                for (int i = 0, size = futures.size(); i < size; i++)
                    futures.get(i).cancel(false);
        }
    }

    public ForkJoinWorkerThreadFactory getFactory() {
        return factory;
    }

    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return ueh;
    }

    public int getParallelism() {
        int par;
        return ((par = parallelism) > 0) ? par : 1;
    }

    public static int getCommonPoolParallelism() {
        return commonParallelism;
    }

    public int getPoolSize() {
        return parallelism + (short)(ctl >>> TC_SHIFT);
    }

    public boolean getAsyncMode() {
        return mode == FIFO_QUEUE;
    }

    public int getRunningThreadCount() {
        int rc = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && w.isApparentlyUnblocked())
                    ++rc;
            }
        }
        return rc;
    }

    public int getActiveThreadCount() {
        int r = parallelism + (int)(ctl >> AC_SHIFT);
        return (r <= 0) ? 0 : r; // suppress momentarily negative values
    }

    public boolean isQuiescent() {
        return parallelism + (int)(ctl >> AC_SHIFT) <= 0;
    }

    public long getStealCount() {
        long count = stealCount;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.nsteals;
            }
        }
        return count;
    }

    public long getQueuedTaskCount() {
        long count = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.queueSize();
            }
        }
        return count;
    }

    public int getQueuedSubmissionCount() {
        int count = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.queueSize();
            }
        }
        return count;
    }

    public boolean hasQueuedSubmissions() {
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && !w.isEmpty())
                    return true;
            }
        }
        return false;
    }

    protected ForkJoinTask<?> pollSubmission() {
        WorkQueue[] ws; WorkQueue w; ForkJoinTask<?> t;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && (t = w.poll()) != null)
                    return t;
            }
        }
        return null;
    }

    protected int drainTasksTo(Collection<? super ForkJoinTask<?>> c) {
        int count = 0;
        WorkQueue[] ws; WorkQueue w; ForkJoinTask<?> t;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; ++i) {
                if ((w = ws[i]) != null) {
                    while ((t = w.poll()) != null) {
                        c.add(t);
                        ++count;
                    }
                }
            }
        }
        return count;
    }

    public String toString() {
        long qt = 0L, qs = 0L; int rc = 0;
        long st = stealCount;
        long c = ctl;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; ++i) {
                if ((w = ws[i]) != null) {
                    int size = w.queueSize();
                    if ((i & 1) == 0)
                        qs += size;
                    else {
                        qt += size;
                        st += w.nsteals;
                        if (w.isApparentlyUnblocked())
                            ++rc;
                    }
                }
            }
        }
        int pc = parallelism;
        int tc = pc + (short)(c >>> TC_SHIFT);
        int ac = pc + (int)(c >> AC_SHIFT);
        if (ac < 0) // ignore transient negative
            ac = 0;
        String level;
        if ((c & STOP_BIT) != 0)
            level = (tc == 0) ? "Terminated" : "Terminating";
        else
            level = plock < 0 ? "Shutting down" : "Running";
        return super.toString() +
            "[" + level +
            ", parallelism = " + pc +
            ", size = " + tc +
            ", active = " + ac +
            ", running = " + rc +
            ", steals = " + st +
            ", tasks = " + qt +
            ", submissions = " + qs +
            "]";
    }

    public void shutdown() {
        checkPermission();
        tryTerminate(false, true);
    }

    public List<Runnable> shutdownNow() {
        checkPermission();
        tryTerminate(true, true);
        return Collections.emptyList();
    }

    public boolean isTerminated() {
        long c = ctl;
        return ((c & STOP_BIT) != 0L &&
                (short)(c >>> TC_SHIFT) + parallelism <= 0);
    }

    public boolean isTerminating() {
        long c = ctl;
        return ((c & STOP_BIT) != 0L &&
                (short)(c >>> TC_SHIFT) + parallelism > 0);
    }

    public boolean isShutdown() {
        return plock < 0;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (this == common) {
            awaitQuiescence(timeout, unit);
            return false;
        }
        long nanos = unit.toNanos(timeout);
        if (isTerminated())
            return true;
        if (nanos <= 0L)
            return false;
        long deadline = System.nanoTime() + nanos;
        synchronized (this) {
            for (;;) {
                if (isTerminated())
                    return true;
                if (nanos <= 0L)
                    return false;
                long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
                wait(millis > 0L ? millis : 1L);
                nanos = deadline - System.nanoTime();
            }
        }
    }

    public boolean awaitQuiescence(long timeout, TimeUnit unit) {
        long nanos = unit.toNanos(timeout);
        ForkJoinWorkerThread wt;
        Thread thread = Thread.currentThread();
        if ((thread instanceof ForkJoinWorkerThread) &&
            (wt = (ForkJoinWorkerThread)thread).pool == this) {
            helpQuiescePool(wt.workQueue);
            return true;
        }
        long startTime = System.nanoTime();
        WorkQueue[] ws;
        int r = 0, m;
        boolean found = true;
        while (!isQuiescent() && (ws = workQueues) != null &&
               (m = ws.length - 1) >= 0) {
            if (!found) {
                if ((System.nanoTime() - startTime) > nanos)
                    return false;
                Thread.yield(); // cannot block
            }
            found = false;
            for (int j = (m + 1) << 2; j >= 0; --j) {
                ForkJoinTask<?> t; WorkQueue q; int b;
                if ((q = ws[r++ & m]) != null && (b = q.base) - q.top < 0) {
                    found = true;
                    if ((t = q.pollAt(b)) != null)
                        t.doExec();
                    break;
                }
            }
        }
        return true;
    }

    static void quiesceCommonPool() {
        common.awaitQuiescence(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    public static interface ManagedBlocker {
        boolean block() throws InterruptedException;

        boolean isReleasable();
    }

    public static void managedBlock(ManagedBlocker blocker)
        throws InterruptedException {
        Thread t = Thread.currentThread();
        if (t instanceof ForkJoinWorkerThread) {
            ForkJoinPool p = ((ForkJoinWorkerThread)t).pool;
            while (!blocker.isReleasable()) {
                if (p.tryCompensate(p.ctl)) {
                    try {
                        do {} while (!blocker.isReleasable() &&
                                     !blocker.block());
                    } finally {
                        p.incrementActiveCount();
                    }
                    break;
                }
            }
        }
        else {
            do {} while (!blocker.isReleasable() &&
                         !blocker.block());
        }
    }


    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new ForkJoinTask.AdaptedRunnable<T>(runnable, value);
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new ForkJoinTask.AdaptedCallable<T>(callable);
    }

    private static final sun.misc.Unsafe U;
    private static final long CTL;
    private static final long PARKBLOCKER;
    private static final int ABASE;
    private static final int ASHIFT;
    private static final long STEALCOUNT;
    private static final long PLOCK;
    private static final long INDEXSEED;
    private static final long QBASE;
    private static final long QLOCK;

    static {
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> k = ForkJoinPool.class;
            CTL = U.objectFieldOffset
                (k.getDeclaredField("ctl"));
            STEALCOUNT = U.objectFieldOffset
                (k.getDeclaredField("stealCount"));
            PLOCK = U.objectFieldOffset
                (k.getDeclaredField("plock"));
            INDEXSEED = U.objectFieldOffset
                (k.getDeclaredField("indexSeed"));
            Class<?> tk = Thread.class;
            PARKBLOCKER = U.objectFieldOffset
                (tk.getDeclaredField("parkBlocker"));
            Class<?> wk = WorkQueue.class;
            QBASE = U.objectFieldOffset
                (wk.getDeclaredField("base"));
            QLOCK = U.objectFieldOffset
                (wk.getDeclaredField("qlock"));
            Class<?> ak = ForkJoinTask[].class;
            ABASE = U.arrayBaseOffset(ak);
            int scale = U.arrayIndexScale(ak);
            if ((scale & (scale - 1)) != 0)
                throw new Error("data type scale not a power of two");
            ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);
        } catch (Exception e) {
            throw new Error(e);
        }

        defaultForkJoinWorkerThreadFactory =
            new DefaultForkJoinWorkerThreadFactory();
        modifyThreadPermission = new RuntimePermission("modifyThread");

        common = java.security.AccessController.doPrivileged
            (new java.security.PrivilegedAction<ForkJoinPool>() {
                public ForkJoinPool run() { return makeCommonPool(); }});
        int par = common.parallelism; // report 1 even if threads disabled
        commonParallelism = par > 0 ? par : 1;
    }

    private static ForkJoinPool makeCommonPool() {
        int parallelism = -1;
        ForkJoinWorkerThreadFactory factory = null;
        UncaughtExceptionHandler handler = null;
        try {  // ignore exceptions in accessing/parsing properties
            String pp = System.getProperty
                ("java.util.concurrent.ForkJoinPool.common.parallelism");
            String fp = System.getProperty
                ("java.util.concurrent.ForkJoinPool.common.threadFactory");
            String hp = System.getProperty
                ("java.util.concurrent.ForkJoinPool.common.exceptionHandler");
            if (pp != null)
                parallelism = Integer.parseInt(pp);
            if (fp != null)
                factory = ((ForkJoinWorkerThreadFactory)ClassLoader.
                           getSystemClassLoader().loadClass(fp).newInstance());
            if (hp != null)
                handler = ((UncaughtExceptionHandler)ClassLoader.
                           getSystemClassLoader().loadClass(hp).newInstance());
        } catch (Exception ignore) {
        }
        if (factory == null) {
            if (System.getSecurityManager() == null)
                factory = defaultForkJoinWorkerThreadFactory;
            else // use security-managed default
                factory = new InnocuousForkJoinWorkerThreadFactory();
        }
        if (parallelism < 0 && // default 1 less than #cores
            (parallelism = Runtime.getRuntime().availableProcessors() - 1) <= 0)
            parallelism = 1;
        if (parallelism > MAX_CAP)
            parallelism = MAX_CAP;
        return new ForkJoinPool(parallelism, factory, handler, LIFO_QUEUE,
                                "ForkJoinPool.commonPool-worker-");
    }

    static final class InnocuousForkJoinWorkerThreadFactory
        implements ForkJoinWorkerThreadFactory {

        private static final AccessControlContext innocuousAcc;
        static {
            Permissions innocuousPerms = new Permissions();
            innocuousPerms.add(modifyThreadPermission);
            innocuousPerms.add(new RuntimePermission(
                                   "enableContextClassLoaderOverride"));
            innocuousPerms.add(new RuntimePermission(
                                   "modifyThreadGroup"));
            innocuousAcc = new AccessControlContext(new ProtectionDomain[] {
                    new ProtectionDomain(null, innocuousPerms)
                });
        }

        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return (ForkJoinWorkerThread.InnocuousForkJoinWorkerThread)
                java.security.AccessController.doPrivileged(
                    new java.security.PrivilegedAction<ForkJoinWorkerThread>() {
                    public ForkJoinWorkerThread run() {
                        return new ForkJoinWorkerThread.
                            InnocuousForkJoinWorkerThread(pool);
                    }}, innocuousAcc);
        }
    }

}
