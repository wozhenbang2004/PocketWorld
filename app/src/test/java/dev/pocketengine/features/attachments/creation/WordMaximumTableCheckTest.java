package dev.pocketengine.features.attachments.creation;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
/** Only the newly exposed supported-capacity boundary; older green cases are not rerun here. */
public final class WordMaximumTableCheckTest {
    @Test public void maximumSupportedFiveHundredBySixteenTableChecksEveryCellWithoutDroppingTheTail()throws Exception{
        String row=String.join("\t",java.util.Collections.nCopies(16,"x"));String body=String.join("\n",java.util.Collections.nCopies(500,row));assertEquals(15999,body.length());var draft=new DocumentDraft(new JSONObject().put("format","docx").put("body",body));byte[] bytes=WordDocumentWriter.write(draft,new RunControl());assertTrue(WordDocumentWriter.check(bytes,draft,new RunControl()).getBoolean("structuralReadComplete"));
        var last=new DocumentReader(null,null).read(bytes,new DocumentContent.Request(7999,0,2,2048,2),new RunControl());assertEquals(8000,last.totalUnits);assertEquals(2,last.chunks.size());assertEquals(7999,last.chunks.get(0).unit);assertEquals(8000,last.chunks.get(1).unit);assertEquals("x",last.chunks.get(1).text);
    }
}
