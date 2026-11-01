package dev.pocketengine.phone;

import android.content.Context;
import android.view.Display;

/** Main-screen observation source only; it is never attached as the original task's execution port. */
public final class AndroidMainScreenContextSource implements OwnerScreenContext.Source {
    private final AndroidPhonePort reader;
    public AndroidMainScreenContextSource(Context context) { reader = new AndroidPhonePort(context); }
    @Override public OwnerScreenContext.SourceIdentity identity() throws Exception {
        PhoneAccessibilityService service = PhoneAccessibilityService.get();
        if (service == null) throw new OwnerScreenContext.Failure("service_disconnected", "请先连接手机界面读取服务");
        return new OwnerScreenContext.SourceIdentity(service.observationSessionId(), Display.DEFAULT_DISPLAY);
    }
    @Override public OwnerScreenContext.Captured capture(boolean includeScreenshot, OwnerScreenContext.ReadCheck check) throws Exception {
        return reader.captureForContext(includeScreenshot, check);
    }
}
