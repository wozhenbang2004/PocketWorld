package dev.pocketengine.displayhelper;

import java.nio.ByteBuffer;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real production geometry and RGBA mapping; no Android emulation or replacement implementation. */
public final class DisplayGeometryTest {
    private static DisplayGeometry geometry(long generation,int rotation) {
        return new DisplayGeometry(generation,(rotation&1)==0?2:3,(rotation&1)==0?3:2,160,rotation);
    }
    private static ByteBuffer surface(int[] values,int rowStride,int pixelStride,int prefix) {
        byte[] bytes=new byte[prefix+2*rowStride+pixelStride+4];
        for(int y=0;y<3;y++)for(int x=0;x<2;x++) {
            int at=prefix+y*rowStride+x*pixelStride,value=values[y*2+x];
            bytes[at]=(byte)value;bytes[at+1]=(byte)(value+20);bytes[at+2]=(byte)(value+40);bytes[at+3]=(byte)255;
        }
        ByteBuffer data=ByteBuffer.wrap(bytes);data.position(prefix);return data;
    }
    private static void pixels(int[] expected,byte[] actual) {
        assertEquals(expected.length*4,actual.length);
        for(int i=0;i<expected.length;i++) {
            assertEquals(expected[i],actual[i*4]&255);assertEquals(expected[i]+20,actual[i*4+1]&255);
            assertEquals(expected[i]+40,actual[i*4+2]&255);assertEquals(255,actual[i*4+3]&255);
        }
    }
    @Test public void naturalSurfaceIsMappedOnceIntoEachLogicalOrientation() {
        ByteBuffer data=surface(new int[]{1,2,3,4,5,6},8,4,0);
        // The same physical buffer is inverse-projected into logical rows. No stretching.
        pixels(new int[]{1,2,3,4,5,6},geometry(1,0).logicalPixels(data,4,8,2,3));
        pixels(new int[]{2,4,6,1,3,5},geometry(2,1).logicalPixels(data,4,8,2,3));
        pixels(new int[]{6,5,4,3,2,1},geometry(3,2).logicalPixels(data,4,8,2,3));
        pixels(new int[]{5,3,1,6,4,2},geometry(4,3).logicalPixels(data,4,8,2,3));
    }
    @Test public void rowPaddingPixelStrideAndBufferPositionNeverBecomePixels() {
        ByteBuffer data=surface(new int[]{1,2,3,4,5,6},24,8,7);
        pixels(new int[]{2,4,6,1,3,5},geometry(2,1).logicalPixels(data,8,24,2,3));
        assertEquals(7,data.position());
    }
    @Test public void mismatchedOrTruncatedPhysicalFramesAreRejected() {
        DisplayGeometry portrait=geometry(1,0);ByteBuffer data=surface(new int[]{1,2,3,4,5,6},8,4,0);
        assertThrows(IllegalArgumentException.class,()->portrait.logicalPixels(data,4,8,3,2));
        assertThrows(IllegalArgumentException.class,()->portrait.logicalPixels(data,3,8,2,3));
        assertThrows(IllegalArgumentException.class,()->portrait.logicalPixels(data,4,7,2,3));
        data.limit(data.limit()-1);
        assertThrows(IllegalArgumentException.class,()->portrait.logicalPixels(data,4,8,2,3));
    }
    @Test public void landscapeKeepsNaturalCaptureExtentWithoutResizeFeedback() {
        DisplayGeometry portrait=new DisplayGeometry(1,720,1280,240,0);
        DisplayGeometry landscape=new DisplayGeometry(2,1280,720,240,1);
        assertEquals(portrait.captureWidth,landscape.captureWidth);assertEquals(portrait.captureHeight,landscape.captureHeight);
        assertFalse(portrait.sameConfiguration(landscape));
        DisplayGeometry resized=new DisplayGeometry(3,960,640,320,1);
        assertEquals(640,resized.captureWidth);assertEquals(960,resized.captureHeight);
        assertFalse(landscape.sameConfiguration(resized));
    }
    @Test public void sameSizeHalfTurnAndRoundTripInvalidateOriginalObservation() {
        DisplayGeometry before=geometry(1,0),halfTurn=geometry(2,2),returned=geometry(3,0);
        assertEquals(before.width,halfTurn.width);assertEquals(before.height,halfTurn.height);
        assertFalse(before.sameObservation(halfTurn));assertFalse(before.sameObservation(returned));
        assertTrue(before.sameConfiguration(returned));
    }
    @Test public void delayedRepliesCannotRollBackOrRedefineAGeneration() {
        DisplayGeometry original=geometry(1,0),landscape=geometry(2,1);
        assertTrue(landscape.canFollow(original));assertFalse(original.canFollow(landscape));
        assertTrue(geometry(2,1).canFollow(landscape));assertFalse(geometry(2,3).canFollow(landscape));
        assertFalse(new DisplayGeometry(2,3,2,320,1).canFollow(landscape));
    }
    @Test public void completeWireIdentityRoundTripsIncludingNotReady()throws Exception {
        DisplayGeometry original=geometry(7,3);JSONObject wire=original.writeTo(new JSONObject(),false);
        DisplayGeometry parsed=DisplayGeometry.read(wire);
        assertTrue(original.sameObservation(parsed));assertFalse(wire.getBoolean("geometryReady"));
        assertEquals(2,wire.getInt("captureWidth"));assertEquals(3,wire.getInt("captureHeight"));
    }
    @Test public void partialOrForgedGeometryCannotBecomeLegacyCompatibility()throws Exception {
        assertNull(DisplayGeometry.read(new JSONObject().put("width",720).put("height",1280)));
        JSONObject wire=geometry(2,1).writeTo(new JSONObject(),true);
        JSONObject missing=new JSONObject(wire.toString());missing.remove("geometryGeneration");
        assertThrows(IllegalArgumentException.class,()->DisplayGeometry.read(missing));
        JSONObject partial=new JSONObject().put("geometryGeneration",2);
        assertThrows(IllegalArgumentException.class,()->DisplayGeometry.read(partial));
        JSONObject swapped=new JSONObject(wire.toString()).put("captureWidth",3).put("captureHeight",2);
        assertThrows(IllegalArgumentException.class,()->DisplayGeometry.read(swapped));
        JSONObject stringGeneration=new JSONObject(wire.toString()).put("geometryGeneration","2");
        assertThrows(IllegalArgumentException.class,()->DisplayGeometry.read(stringGeneration));
        JSONObject downgrade=new JSONObject(wire.toString()).put("geometryTracking",false);
        assertThrows(IllegalArgumentException.class,()->DisplayGeometry.read(downgrade));
    }
    @Test public void boundsAndExactGenerationRejectUnsafeAllocationOrFractionalIdentity()throws Exception {
        assertThrows(IllegalArgumentException.class,()->new DisplayGeometry(0,2,3,160,0));
        assertThrows(IllegalArgumentException.class,()->new DisplayGeometry(1,Integer.MAX_VALUE,2,160,0));
        assertThrows(IllegalArgumentException.class,()->new DisplayGeometry(1,2,3,160,4));
        JSONObject wire=geometry(1,0).writeTo(new JSONObject(),true).put("geometryGeneration",1.5);
        assertThrows(IllegalArgumentException.class,()->DisplayGeometry.read(wire));
        wire.put("geometryGeneration",new java.math.BigInteger("9223372036854775808"));
        assertThrows(IllegalArgumentException.class,()->DisplayGeometry.read(wire));
    }
}
