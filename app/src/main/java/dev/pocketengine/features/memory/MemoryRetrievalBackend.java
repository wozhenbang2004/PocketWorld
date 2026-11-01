package dev.pocketengine.features.memory;

import java.util.List;
import org.json.JSONObject;

/** Disposable candidate index. Only MemoryBook may admit a result or return authoritative text. */
public interface MemoryRetrievalBackend extends AutoCloseable {
    void synchronize(List<Document> currentScopeDocuments)throws Exception;
    List<Match> find(String ownerId,String spaceId,String query,int limit)throws Exception;
    void invalidate()throws Exception;
    JSONObject status()throws Exception;
    @Override void close()throws Exception;

    final class Document {
        public final String ownerId,spaceId,id,text;
        public final long revision,updatedAt;
        public final double confidence;
        public Document(String ownerId,String spaceId,String id,long revision,String text,long updatedAt,double confidence){
            this.ownerId=ownerId;this.spaceId=spaceId;this.id=id;this.revision=revision;this.text=text;this.updatedAt=updatedAt;this.confidence=confidence;
        }
    }
    final class Match {
        public final String id;public final long revision;
        public Match(String id,long revision){this.id=id;this.revision=revision;}
    }
}
