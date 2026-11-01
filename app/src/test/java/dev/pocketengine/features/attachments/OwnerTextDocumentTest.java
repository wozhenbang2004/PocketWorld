package dev.pocketengine.features.attachments;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.*;
import dev.pocketengine.core.RunControl;
import org.json.JSONObject;
import org.junit.Test;

public final class OwnerTextDocumentTest {
    private static OwnerTextDocument document(byte[] bytes){return new OwnerTextDocument(()->new ByteArrayInputStream(bytes));}
    @Test public void exactUnicodePaginationDoesNotSplitCharactersOrOverwriteOriginal()throws Exception {
        String text="a".repeat(11999)+"😀"+" tail";byte[] original=text.getBytes(StandardCharsets.UTF_8);var read=document(original);JSONObject first=read.page("UTF-8",0,12000,new RunControl());assertEquals(11999,first.getInt("nextOffset"));assertEquals("😀 tail",read.page("UTF-8",first.getInt("nextOffset"),100,new RunControl()).getString("content"));assertThrows(Exception.class,()->read.page("UTF-8",12000,100,new RunControl()));assertArrayEquals(text.getBytes(StandardCharsets.UTF_8),original);
    }
    @Test public void explicitNonUtf8EncodingReadsRealBytesAndMalformedUtf8DoesNotReplace()throws Exception{
        String text="中文金额45.00\r\n";byte[] bytes=text.getBytes(Charset.forName("GB18030"));var read=document(bytes);assertEquals(text,read.page("GB18030",0,100,new RunControl()).getString("content"));assertThrows(Exception.class,()->read.page("UTF-8",0,100,new RunControl()));assertThrows(Exception.class,()->read.page("UTF-7",0,100,new RunControl()));assertArrayEquals(bytes,text.getBytes(Charset.forName("GB18030")));
    }
    @Test public void streamingLiteralSearchCrossesChunksAndPaginatedHitsKeepExactOffsets()throws Exception{
        String text="x".repeat(4094)+"canary-45.00\n"+"hit\n".repeat(45);var read=document(text.getBytes(StandardCharsets.UTF_8));var found=read.search("UTF-8","canary-45.00",0,new RunControl());assertEquals(4094,found.getJSONArray("matches").getJSONObject(0).getInt("offset"));assertTrue(found.getBoolean("atEnd"));
        var first=read.search("UTF-8","hit",0,new RunControl());assertEquals(40,first.getJSONArray("matches").length());assertFalse(first.getBoolean("atEnd"));var next=read.search("UTF-8","hit",first.getInt("nextOffset"),new RunControl());assertEquals(5,next.getJSONArray("matches").length());assertTrue(next.getBoolean("atEnd"));
    }
    @Test public void cancelledReadersAndInvalidRangesFailRatherThanReturnEmptySuccess()throws Exception{
        var read=document("original".getBytes(StandardCharsets.UTF_8));RunControl stopped=new RunControl();stopped.cancel();assertThrows(Exception.class,()->read.page("UTF-8",0,100,stopped));assertThrows(Exception.class,()->read.search("UTF-8","original",0,stopped));assertThrows(Exception.class,()->read.page("UTF-8",99,100,new RunControl()));assertThrows(Exception.class,()->read.search("UTF-8","",0,new RunControl()));
    }
}
