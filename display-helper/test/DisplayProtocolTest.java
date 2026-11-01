package dev.pocketengine.displayhelper;

import dev.pocketengine.phone.display.DisplayWire;
import java.io.*;

/** Small real framing/lease tests runnable without Android or a model. */
public final class DisplayProtocolTest {
    private static int assertions;
    public static void main(String[] args)throws Exception {
        rejects(()->new LeasePolicy("s",0,0,1000));
        rejects(()->new LeasePolicy("",4,0,1000));
        rejects(()->new LeasePolicy("s",4,0,600001));
        LeasePolicy lease=new LeasePolicy("owner",7,1000,600000);
        check(!lease.accepts("other",7,1001));check(!lease.accepts("owner",0,1001));check(!lease.accepts("owner",8,1001));
        check(lease.accepts("owner",7,2000));check(!lease.expired(121999));check(lease.expired(122001));
        LeasePolicy hard=new LeasePolicy("owner",9,0,5000);check(hard.accepts("owner",9,4999));check(!hard.accepts("owner",9,5000));
        LeasePolicy released=new LeasePolicy("owner",12,0,5000);released.release();check(!released.accepts("owner",12,1));
        String dump="Display #0 (activities from top to bottom):\n  mResumedActivity: ActivityRecord{ com.example.main/.Home }\nDisplay #2 (activities from top to bottom):\n  mResumedActivity: ActivityRecord{ com.example.second/.Home }";
        check(PrimaryAppGuard.uses(dump,"com.example.main"));check(!PrimaryAppGuard.uses(dump,"com.example.second"));
        check(!PrimaryAppGuard.uses(dump,"com.example"));rejects(()->PrimaryAppGuard.uses("Display 0 unknown format","com.example.main"));
        rejects(()->PrimaryAppGuard.uses("Display #0 (activities):\nunknown task state","com.example.main"));
        String text="{\"text\":\"后台中文消息\"}";ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DisplayWire.write(new DataOutputStream(bytes),text,1000);check(text.equals(DisplayWire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),1000)));
        rejects(()->DisplayWire.write(new DataOutputStream(new ByteArrayOutputStream()),text,4));
        for(int size:new int[]{-1,0,1,99999}){ByteArrayOutputStream raw=new ByteArrayOutputStream();new DataOutputStream(raw).writeInt(size);rejects(()->DisplayWire.read(new DataInputStream(new ByteArrayInputStream(raw.toByteArray())),1000));}
        ByteArrayOutputStream malformed=new ByteArrayOutputStream();DataOutputStream raw=new DataOutputStream(malformed);raw.writeInt(2);raw.writeByte(0xc0);raw.writeByte(0xaf);
        rejects(()->DisplayWire.read(new DataInputStream(new ByteArrayInputStream(malformed.toByteArray())),10));
        ByteArrayOutputStream truncated=new ByteArrayOutputStream();new DataOutputStream(truncated).writeInt(4);
        rejects(()->DisplayWire.read(new DataInputStream(new ByteArrayInputStream(truncated.toByteArray())),10));
        System.out.println("Display protocol/lease assertions passed: "+assertions);
    }
    private static void check(boolean condition){assertions++;if(!condition)throw new AssertionError();}
    private static void rejects(Throwing action)throws Exception {assertions++;try{action.run();}catch(Exception expected){return;}throw new AssertionError("Expected rejection");}
    private interface Throwing {void run()throws Exception;}
}
