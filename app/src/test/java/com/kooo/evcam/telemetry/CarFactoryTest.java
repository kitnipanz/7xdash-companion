package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;

import org.junit.Test;

import java.util.concurrent.Executor;

/** How the ECARX car object gets built when {@code Car.create(Context)} is or is not there. */
public class CarFactoryTest {

    /** Stands in for the app context. */
    public static final class FakeContext {
    }

    public interface Managers {
        Object getICarFunction();

        Object getSensorManager();
    }

    /** What the car tells the listener it was given. */
    public interface ConnectionListener {
        void onConnected(boolean ok);
    }

    private static final FakeContext CONTEXT = new FakeContext();

    private static CarFactory.Env env() {
        return new CarFactory.Env() {
            @Override
            public Object context() {
                return CONTEXT;
            }

            @Override
            public Handler handler() {
                return null;
            }

            @Override
            public Looper looper() {
                return null;
            }

            @Override
            public Executor executor() {
                return Runnable::run;
            }
        };
    }

    // ---- the usual head unit: create(Context), ready at once

    public static class UsualCar implements Managers {
        static Object lastContext;

        public static UsualCar create(FakeContext c) {
            lastContext = c;
            return new UsualCar();
        }

        public static UsualCar create(FakeContext c, String unknown) {
            throw new AssertionError("never tried after create(Context) worked");
        }

        @Override
        public Object getICarFunction() {
            return "function";
        }

        @Override
        public Object getSensorManager() {
            return "sensor";
        }
    }

    @Test
    public void theUsualCreateIsTriedFirstAndIsReadyAtOnce() {
        CarFactory.Result r = CarFactory.create(UsualCar.class, env());
        assertNotNull(r.car);
        assertSame(CONTEXT, UsualCar.lastContext);
        assertEquals("create(FakeContext)", r.how);
        assertTrue(r.tries.isEmpty());
        assertEquals("ready at once", r.ready);
    }

    // ---- no create(Context): create(Context, listener) that connects and calls back

    public static class ListenerCar implements Managers {
        private volatile boolean connected;
        private ConnectionListener listener;

        public static ListenerCar create(FakeContext c, ConnectionListener l) {
            ListenerCar car = new ListenerCar();
            car.listener = l;
            return car;
        }

        public void connect() {
            connected = true;
            listener.onConnected(true);
        }

        @Override
        public Object getICarFunction() {
            return connected ? "function" : null;
        }

        @Override
        public Object getSensorManager() {
            return null;
        }
    }

    @Test
    public void aCarThatNeedsAListenerAndConnectIsBuiltAndConnected() {
        CarFactory.Result r = CarFactory.create(ListenerCar.class, env());
        assertNotNull(r.car);
        assertEquals("create(FakeContext, ConnectionListener)", r.how);
        assertTrue(r.ready, r.ready.startsWith("connect() called; ready after"));
        assertTrue("what the car told us is kept", r.ready.contains("onConnected(true)"));
    }

    // ---- getInstance(Context, Executor, long) after a create that gives null

    public static class InstanceCar implements Managers {
        static long timeout;

        public static Object create(FakeContext c) {
            return null;
        }

        public static InstanceCar getInstance(FakeContext c, Executor e, long timeoutMs) {
            assertNotNull(e);
            timeout = timeoutMs;
            return new InstanceCar();
        }

        @Override
        public Object getICarFunction() {
            return null;
        }

        @Override
        public Object getSensorManager() {
            return "sensor";
        }
    }

    @Test
    public void aNullFromCreateMovesOnToTheNextFactory() {
        CarFactory.Result r = CarFactory.create(InstanceCar.class, env());
        assertNotNull(r.car);
        assertEquals("getInstance(FakeContext, Executor, long)", r.how);
        assertEquals(CarFactory.FACTORY_TIMEOUT_MS, InstanceCar.timeout);
        assertEquals(1, r.tries.size());
        assertEquals("create(FakeContext) returned null", r.tries.get(0));
    }

    // ---- nothing we can fill: nothing to call

    public static class StrangeCar {
        private StrangeCar() {
        }

        public static StrangeCar create(String name, int flags) {
            return new StrangeCar();
        }

        public static int version() {
            return 3;
        }
    }

    @Test
    public void factoriesWeCannotFillAreNotCalled() {
        CarFactory.Result r = CarFactory.create(StrangeCar.class, env());
        assertNull(r.car);
        assertFalse("nothing was callable: this head unit has no way in", r.anyCandidate);
        assertEquals("create(String, int): cannot fill java.lang.String", r.triesLine());
    }

    // ---- a factory that throws, then a public constructor

    public static class ThrowingCar implements Managers {
        public ThrowingCar(FakeContext c) {
        }

        public static ThrowingCar create(FakeContext c) {
            throw new IllegalStateException("car service not bound");
        }

        @Override
        public Object getICarFunction() {
            return "function";
        }

        @Override
        public Object getSensorManager() {
            return null;
        }
    }

    @Test
    public void aThrowingFactoryFallsBackToTheConstructor() {
        CarFactory.Result r = CarFactory.create(ThrowingCar.class, env());
        assertNotNull(r.car);
        assertEquals("ThrowingCar(FakeContext)", r.how);
        assertEquals("create(FakeContext) threw IllegalStateException: car service not bound", r.triesLine());
    }

    @Test
    public void theApiListingMarksStaticsAndConstructors() {
        String api = CarFactory.describeApi(ThrowingCar.class);
        assertTrue(api, api.startsWith(ThrowingCar.class.getName() + " implements " + Managers.class.getName()));
        assertTrue(api, api.contains("\n  new ThrowingCar(FakeContext)"));
        assertTrue(api, api.contains("\n  static ThrowingCar create(FakeContext)"));
        assertTrue(api, api.contains("\n  Object getICarFunction()"));
    }
}
