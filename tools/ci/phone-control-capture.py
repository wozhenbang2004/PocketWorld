write(JAVA+'phone/observation/PhoneCaptureGate.java', '''package dev.pocketengine.phone.observation;
import java.util.Objects;
import java.util.function.LongSupplier;
/** Bounded service-scoped screenshot serialization; callers must remain off Android main. */
public final class PhoneCaptureGate {
    public static final long INTERVAL_MS=1000, ACQUIRE_MS=2500;
    @FunctionalInterface public interface Check { void check() throws Exception; }
    @FunctionalInterface public interface Wait { void pause(long millis) throws Exception; }
    public static final class Unavailable extends Exception {
        public final String code;
        private Unavailable(String code){super(code);this.code=code;}
    }
    private final LongSupplier clock; private final Wait wait;
    private Lease active; private boolean closed; private long lastRequest=-1;
    public PhoneCaptureGate(LongSupplier clock){this(clock,Thread::sleep);}
    public PhoneCaptureGate(LongSupplier clock,Wait wait){this.clock=Objects.requireNonNull(clock);this.wait=Objects.requireNonNull(wait);}
    public Lease acquire(Check check)throws Exception {
        Objects.requireNonNull(check);long started=clock.getAsLong();
        for(;;){check.check();long now=clock.getAsLong(),delay;
            synchronized(this){
                if(closed)throw new Unavailable("service_disconnected");
                if(now<started||now-started>=ACQUIRE_MS)throw new Unavailable("screenshot_busy");
                long spacing=lastRequest<0?0:INTERVAL_MS-(now-lastRequest);
                if(active==null&&spacing<=0){active=new Lease();return active;}
                delay=Math.min(50,ACQUIRE_MS-(now-started));
                if(active==null)delay=Math.min(delay,Math.max(1,spacing));
            }
            wait.pause(Math.max(1,delay));
        }
    }
    public synchronized void close(){closed=true;}
    public final class Lease implements AutoCloseable {
        private boolean released;
        private Lease(){}
        public void requested()throws Unavailable {
            synchronized(PhoneCaptureGate.this){
                if(closed||released||active!=this)throw new Unavailable("service_disconnected");
                lastRequest=clock.getAsLong();
            }
        }
        @Override public void close(){synchronized(PhoneCaptureGate.this){if(released)return;released=true;if(active==this)active=null;}}
    }
}
''')
replace(JAVA+'phone/PhoneAccessibilityService.java','    private static volatile PhoneAccessibilityService instance;','''    private static volatile PhoneAccessibilityService instance;
    private volatile dev.pocketengine.phone.observation.PhoneCaptureGate captures =
            new dev.pocketengine.phone.observation.PhoneCaptureGate(android.os.SystemClock::uptimeMillis);
    dev.pocketengine.phone.observation.PhoneCaptureGate captureGate() { return captures; }''')
replace(JAVA+'phone/PhoneAccessibilityService.java','private final String observationSessionId =','private volatile String observationSessionId =')
replace(JAVA+'phone/PhoneAccessibilityService.java','private final PhoneUiSignals changeSignals=','private volatile PhoneUiSignals changeSignals=')
replace(JAVA+'phone/PhoneAccessibilityService.java','    @Override protected void onServiceConnected() { instance = this; }','''    @Override protected void onServiceConnected() {
        // A reconnection may reuse the same Java object, but never its observation generation.
        changeSignals.close(); captures.close();
        observationSessionId = java.util.UUID.randomUUID().toString();
        changeSignals = new PhoneUiSignals(observationSessionId, android.os.SystemClock::uptimeMillis);
        captures = new dev.pocketengine.phone.observation.PhoneCaptureGate(android.os.SystemClock::uptimeMillis);
        instance = this;
        dev.pocketengine.phone.setup.PhoneSetupModule.onAccessibilityConnected();
    }''')
replace(JAVA+'phone/PhoneAccessibilityService.java','        changeSignals.close();\n','        changeSignals.close();\n        captures.close();\n')
replace(JAVA+'phone/setup/PhoneSetupModule.java','    /** Called after the original accessibility service has invalidated its observations and cancelled its run. */','''    /** Publish connection facts only. A rebind never grants authority or resumes a task. */
    public static void onAccessibilityConnected() {
        PhoneSetupModule module = current();
        if (module != null && !module.closed) module.emit("accessibility_connected");
    }
    /** Called after the original accessibility service has invalidated its observations and cancelled its run. */''')
replace(JAVA+'phone/setup/PhoneSetupModule.java','        if (module != null) module.recoverAfterLifecycle(() -> { });','''        if (module != null) {
            module.emit("accessibility_disconnected");
            module.recoverAfterLifecycle(() -> { });
        }''')
replace(JAVA+'phone/AndroidPhonePort.java','import dev.pocketengine.phone.observation.PhoneReadGate;','import dev.pocketengine.phone.observation.PhoneReadGate;\nimport dev.pocketengine.phone.observation.PhoneCaptureGate;')
replace(JAVA+'phone/AndroidPhonePort.java','    private volatile long lastScreenshotRequest;\n','')
p=ROOT/(JAVA+'phone/AndroidPhonePort.java');s=p.read_text()
a=s.index('            Tree after = null;');b=s.index('            control.check();',a)
s=s[:a]+'''            String serviceSession = service.observationSessionId();
            OwnerScreenContext.ReadCheck observationCheck = () -> {
                guard(service, control, revision);
                if (!serviceSession.equals(service.observationSessionId()))
                    throw new PhoneFailure("service_disconnected", "界面读取服务已经重连，旧观察已丢弃。");
            };
            Tree after; CapturedImage image;
            Object observationHide = new Object();
            // Wait for the actual shared screenshot slot before collecting the first tree.
            // Hold our own overlay hidden through tree/pixels/tree; no driver-level hidden retries.
            try (PhoneCaptureGate.Lease lease = includeImage ? service.captureGate().acquire(observationCheck::check) : null) {
                try {
                    if (includeImage) {
                        onMainRead(observationCheck::check, () -> { service.hideForCapture(observationHide); return null; });
                        sleepRead(observationCheck::check, 120);
                    }
                    Tree before = onTreeRead(observationCheck::check, () -> readTree(service));
                    image = includeImage ? capture(service, observationCheck::check, lease) : null;
                    after = includeImage ? onTreeRead(observationCheck::check, () -> readTree(service)) : before;
                    if (!before.sameScreen(after) || image != null && (image.fullWidth != after.screen.width || image.fullHeight != after.screen.height))
                        throw new PhoneFailure("screen_changed", "页面在观察期间变化，当前读取已丢弃。");
                } finally { if (includeImage) main.post(() -> service.restoreAfterCapture(observationHide)); }
            }
''' +s[b:]
a=s.index('    @Override public synchronized JSONObject observe(');b=s.index('    @Override public synchronized JSONObject scrollNode(',a)
part=s[a:b].replace('service != PhoneAccessibilityService.get())','service != PhoneAccessibilityService.get() || !serviceSession.equals(service.observationSessionId()))')
part=part.replace('new Snapshot(service, after, control, revision, image)','new Snapshot(service, serviceSession, after, control, revision, image)')
part=part.replace('.put("sessionId", service.observationSessionId())','.put("sessionId", serviceSession)')
s=s[:a]+part+s[b:];p.write_text(s)
replace(JAVA+'phone/AndroidPhonePort.java','''        } catch (PhoneFailure failure) { return failure(failure.code, failure.getMessage()); }
        catch (SecurityException failure)''','''        } catch (PhoneCaptureGate.Unavailable blocked) {
            return failure(blocked.code, "service_disconnected".equals(blocked.code) ? AndroidPhoneReadiness.serviceUnavailableMessage(context) : "另一项截图读取尚未结束，稍后重新读取。");
        } catch (PhoneFailure failure) { return failure(failure.code, failure.getMessage()); }
        catch (SecurityException failure)''')
replace(JAVA+'phone/AndroidPhonePort.java','''    private CapturedImage capture(PhoneAccessibilityService service, OwnerScreenContext.ReadCheck check) throws Exception {
        // The platform may throttle screenshots. Space our requests; never retry a gesture on capture failure.
        long spacing = 1_000 - (SystemClock.uptimeMillis() - lastScreenshotRequest);
        if (spacing > 0) sleepRead(check, spacing);
        Object hideToken = new Object();''','''    private CapturedImage capture(PhoneAccessibilityService service, OwnerScreenContext.ReadCheck check) throws Exception {
        try (PhoneCaptureGate.Lease lease = service.captureGate().acquire(check::check)) {
            return capture(service, check, lease);
        } catch (PhoneCaptureGate.Unavailable blocked) {
            throw new PhoneFailure(blocked.code, "截图读取暂不可用，未派发依赖截图的操作。");
        }
    }
    private CapturedImage capture(PhoneAccessibilityService service, OwnerScreenContext.ReadCheck check, PhoneCaptureGate.Lease lease) throws Exception {
        Object hideToken = new Object();''')
replace(JAVA+'phone/AndroidPhonePort.java','                        lastScreenshotRequest = SystemClock.uptimeMillis();','                        lease.requested();')
replace(JAVA+'phone/AndroidPhonePort.java','if(reference.get()!=expected||expected.service!=service||expected.control!=control','if(reference.get()!=expected||expected.service!=service||!expected.serviceSessionId.equals(service.observationSessionId())||expected.control!=control')
replace(JAVA+'phone/AndroidPhonePort.java','''        final PhoneAccessibilityService service;
        final Tree tree;''','''        final PhoneAccessibilityService service;
        final String serviceSessionId;
        final Tree tree;''')
replace(JAVA+'phone/AndroidPhonePort.java','Snapshot(PhoneAccessibilityService service, Tree tree, RunControl control, long revision, CapturedImage image)','Snapshot(PhoneAccessibilityService service, String serviceSessionId, Tree tree, RunControl control, long revision, CapturedImage image)')
replace(JAVA+'phone/AndroidPhonePort.java','            this.service = service; this.tree = tree;','            this.service = service; this.serviceSessionId = serviceSessionId; this.tree = tree;')

p=ROOT/(JAVA+'phone/display/BackgroundPhonePort.java');s=p.read_text()
assert s.count('expected.service!=PhoneAccessibilityService.get()')==3
s=s.replace('expected.service!=PhoneAccessibilityService.get()','!expected.serviceCurrent()')
s=s.replace('final PhoneAccessibilityService service;final boolean imageIncluded;', 'final PhoneAccessibilityService service;final String accessibilitySessionId;final boolean imageIncluded;')
s=s.replace('this.service=PhoneAccessibilityService.get();this.imageIncluded=imageIncluded;this.geometry=tree.geometry;this.displayRevision=tree.displayRevision;}}', 'this.service=tree.service;this.accessibilitySessionId=tree.accessibilitySessionId;this.imageIncluded=imageIncluded;this.geometry=tree.geometry;this.displayRevision=tree.displayRevision;}\n        boolean serviceCurrent(){return service!=null&&service==PhoneAccessibilityService.get()&&accessibilitySessionId.equals(service.observationSessionId());}}')
s=s.replace('if(service==null)throw new PhoneFailure("service_disconnected","无障碍权限已断开，请恢复后继续原任务。");\n        JSONObject state=', 'if(service==null)throw new PhoneFailure("service_disconnected","无障碍权限已断开，请恢复后继续原任务。");\n        String accessibilitySession=service.observationSessionId();\n        JSONObject state=')
s=s.replace('PhoneReadGate.checkCurrent();if(service!=PhoneAccessibilityService.get())throw new PhoneFailure("service_disconnected","无障碍会话已变化。");', 'PhoneReadGate.checkCurrent();if(service!=PhoneAccessibilityService.get()||!accessibilitySession.equals(service.observationSessionId()))throw new PhoneFailure("service_disconnected","无障碍会话已变化。");')
s=s.replace('return new Tree(forest,geometry,displayRevision,width,height);','return new Tree(forest,geometry,displayRevision,width,height,service,accessibilitySession);')
s=s.replace('final DisplayGeometry geometry;final long displayRevision;final int width,height;\n        Tree(', 'final DisplayGeometry geometry;final long displayRevision;final int width,height;\n        final PhoneAccessibilityService service;final String accessibilitySessionId;\n        Tree(')
s=s.replace('Tree(AccessibilityForest forest,DisplayGeometry geometry,long displayRevision,int width,int height){this.forest=forest;', 'Tree(AccessibilityForest forest,DisplayGeometry geometry,long displayRevision,int width,int height,PhoneAccessibilityService service,String accessibilitySession){this.service=service;this.accessibilitySessionId=accessibilitySession;this.forest=forest;')
s=s.replace('checkGeometry(observed.geometry,observed.width,observed.height,observed.displayRevision);current=observed;', 'checkGeometry(observed.geometry,observed.width,observed.height,observed.displayRevision);if(!observed.serviceCurrent())return failure("service_disconnected","界面读取服务已经重连，旧观察已丢弃。");current=observed;')
s=s.replace('current=observed;cached=new JSONObject(out.toString());return out;', 'if(!observed.serviceCurrent())return failure("service_disconnected","界面读取服务已经重连，旧观察已丢弃。");\n            current=observed;cached=new JSONObject(out.toString());return out;')
p.write_text(s)
