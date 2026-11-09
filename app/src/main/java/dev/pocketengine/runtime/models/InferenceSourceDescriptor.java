package dev.pocketengine.runtime.models;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Public source identity only. These labels neither validate credentials nor authorize a request. */
public final class InferenceSourceDescriptor {
    private static final Set<String> API_PROTOCOLS=Set.of("openai_chat","openai_responses","anthropic","gemini");
    private InferenceSourceDescriptor() {}

    public static JSONObject forApi(String protocol,String baseUrl,String bedrockRegion,String credentialState)throws JSONException {
        boolean bedrock="bedrock_anthropic".equals(protocol);
        if(!bedrock&&!API_PROTOCOLS.contains(protocol))throw new IllegalArgumentException("unsupported_api_source_protocol");
        String kind="third_party_gateway",provider="custom";
        URI endpoint=endpoint(baseUrl);
        if(endpoint!=null){
            String host=endpoint.getHost().toLowerCase(Locale.ROOT);
            if(loopback(host))kind="local_gateway";
            else if("https".equalsIgnoreCase(endpoint.getScheme())&&(endpoint.getPort()==-1||endpoint.getPort()==443)){
                if(("openai_chat".equals(protocol)||"openai_responses".equals(protocol))&&"api.openai.com".equals(host)){
                    kind="official_api";provider="openai";
                }else if("anthropic".equals(protocol)&&"api.anthropic.com".equals(host)){
                    kind="official_api";provider="anthropic";
                }else if("gemini".equals(protocol)&&"generativelanguage.googleapis.com".equals(host)){
                    kind="official_api";provider="gemini";
                }else if(bedrock&&bedrockRegion!=null&&!bedrockRegion.isEmpty()
                        &&host.equals("bedrock-runtime."+bedrockRegion+".amazonaws.com")){
                    kind="cloud_provider";provider="aws_bedrock";
                }
            }
        }
        return descriptor(kind,provider,bedrock?"aws_sts_session":"api_key",!bedrock,credentialState);
    }

    public static JSONObject forChatGptAccount(String credentialState,boolean usageLimited)throws JSONException {
        return descriptor("subscription_account","chatgpt","oauth",false,credentialState)
            .put("quota",new JSONObject().put("state",usageLimited?"limited":"unknown"));
    }

    private static JSONObject descriptor(String kind,String provider,String authentication,boolean relay,String state)throws JSONException {
        return new JSONObject().put("kind",kind).put("providerId",provider).put("authentication",authentication)
            .put("appInference",true).put("externalRelayEligible",relay).put("credentialState",state);
    }

    private static URI endpoint(String value){
        try{
            URI uri=new URI(value);
            if(uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null
                    ||!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme())))return null;
            return uri;
        }catch(Exception invalid){return null;}
    }

    /** Parse literals only: no hostname resolution or network access on a public snapshot. */
    private static boolean loopback(String host){
        if("localhost".equals(host))return true;
        if(!host.startsWith("[")){
            int[] address=ipv4(host);
            return address!=null&&address[0]==127;
        }
        String literal=host.substring(1,host.length()-1);
        // Scoped literals are unnecessary for loopback and are not used as identity evidence.
        if(literal.indexOf('%')>=0)return false;
        String[] halves=literal.split("::",-1);
        List<Integer> words=new ArrayList<>();
        for(int side=0;side<halves.length;side++){
            if(!halves[side].isEmpty())for(String part:halves[side].split(":")){
                if(part.indexOf('.')>=0){
                    int[] v4=ipv4(part);if(v4==null)return false;
                    words.add(v4[0]*256+v4[1]);words.add(v4[2]*256+v4[3]);
                }else words.add(Integer.parseInt(part,16));
            }
            if(side==0&&halves.length==2){
                int right=halves[1].isEmpty()?0:halves[1].split(":").length+(halves[1].indexOf('.')>=0?1:0);
                while(words.size()<8-right)words.add(0);
            }
        }
        if(words.size()!=8)return false;
        for(int i=0;i<5;i++)if(words.get(i)!=0)return false;
        return words.get(5)==0&&words.get(6)==0&&words.get(7)==1
            ||words.get(5)==65535&&(words.get(6)>>>8)==127;
    }

    private static int[] ipv4(String host){
        String[] parts=host.split("\\.",-1);if(parts.length!=4)return null;
        int[] address=new int[4];
        for(int i=0;i<4;i++){
            if(!parts[i].matches("0|[1-9][0-9]{0,2}"))return null;
            address[i]=Integer.parseInt(parts[i]);if(address[i]>255)return null;
        }
        return address;
    }
}
