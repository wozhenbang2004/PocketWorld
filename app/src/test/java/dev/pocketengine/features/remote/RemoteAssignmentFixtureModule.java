package dev.pocketengine.features.remote;
import dev.pocketengine.features.FeatureContext;
/** Explicit test credential seam, not a production fake transport or authority. */
public final class RemoteAssignmentFixtureModule {
    private RemoteAssignmentFixtureModule(){}
    public static void markConfigured(android.content.Context context,String profile,String base)throws Exception {
        // Presence only: AndroidKeyStore is deliberately not claimed by this JVM fixture.
        // Use the production slot function; the explicit CredentialReader supplies the fixture token.
        java.lang.reflect.Method slot=RemoteModule.class.getDeclaredMethod("slot",String.class,String.class);slot.setAccessible(true);
        context.getSharedPreferences("feature_secrets",android.content.Context.MODE_PRIVATE).edit().putString((String)slot.invoke(null,profile,base),"explicit-injected-fixture-presence-not-a-ciphertext").commit();
    }
    public static RemoteModule open(FeatureContext context){return new RemoteModule(context,(id,base)->"native-assignment-fixture-token-longer-than-32-characters",path->{});}
}
