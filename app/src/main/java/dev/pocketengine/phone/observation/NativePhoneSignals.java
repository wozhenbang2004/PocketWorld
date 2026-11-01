package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneUiSignals;

/** Optional native event source for the exact selected port; hints confer no action or observation authority. */
public interface NativePhoneSignals {
    PhoneUiSignals uiSignals(RunControl originalControl)throws Exception;
}
