package dev.pocketengine.features.remote;

import dev.pocketengine.features.FeatureContext;

/** Test-only same-package constructor access. Virtual paired credential, original transport. */
public final class HarnessGatewayRemoteFixture {
    private HarnessGatewayRemoteFixture(){}
    public static RemoteModule pairForTest(FeatureContext context,String virtualPairedToken,String profile,String base)throws Exception {
        RemoteModule module=new RemoteModule(context,(id,url)->virtualPairedToken,path->{});
        module.handle("saveProfile",new org.json.JSONObject().put("id",profile).put("name","受控电脑").put("baseUrl",base));
        // Same original input/result fixture boundary: presence metadata + injected virtual reader.
        // This does NOT verify AndroidKeyStore encryption or configure any real account.
        var slot=RemoteModule.class.getDeclaredMethod("slot",String.class,String.class);slot.setAccessible(true);
        if(!context.android.getSharedPreferences("feature_secrets",android.content.Context.MODE_PRIVATE).edit().putString((String)slot.invoke(null,profile,base),"fixture-presence-only").commit())throw new IllegalStateException("fixture metadata commit failed");
        return module;
    }
}
