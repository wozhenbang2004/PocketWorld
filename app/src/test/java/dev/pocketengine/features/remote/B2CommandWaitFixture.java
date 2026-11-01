package dev.pocketengine.features.remote;

import dev.pocketengine.features.FeatureContext;

/** Test-only fixture token reader; the actual transport/watch/TaskStore event path stays intact. */
public final class B2CommandWaitFixture {
    private B2CommandWaitFixture(){}
    public static RemoteModule open(FeatureContext context,String fixtureToken){
        return new RemoteModule(context,(id,origin)->fixtureToken,path->{});
    }
}
