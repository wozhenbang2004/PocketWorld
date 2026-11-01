package dev.pocketengine.media.voice;

import java.net.URI;

/** Owner-selected OpenAI Realtime GA protocol endpoint. This object never holds a credential. */
public final class RealtimeVoiceProfile {
    public final String endpointUrl,model,voice,transcriptionModel;
    public final long revision;
    public RealtimeVoiceProfile(String endpointUrl,String model,String voice,long revision)throws Exception{
        this(endpointUrl,model,voice,"",revision);
    }
    public RealtimeVoiceProfile(String endpointUrl,String model,String voice,String transcriptionModel,long revision)throws Exception{
        URI uri=new URI(endpointUrl);
        if(endpointUrl.length()>2048||!"wss".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getFragment()!=null||uri.getPort()==0||uri.getPort()>65535)
            throw new IllegalArgumentException("实时语音端点须为不带凭据或查询参数的WSS地址");
        if(model==null||!model.matches("[A-Za-z0-9_.:-]{1,120}")||voice==null||!voice.matches("[A-Za-z0-9_-]{1,80}")||revision<1)
            throw new IllegalArgumentException("请填写该服务实际支持的实时语音模型与声音");
        if(transcriptionModel==null||!transcriptionModel.isEmpty()&&!transcriptionModel.matches("[A-Za-z0-9_.:-]{1,120}"))throw new IllegalArgumentException("输入转录模型标识无效");
        this.endpointUrl=uri.toString();this.model=model;this.voice=voice;this.transcriptionModel=transcriptionModel;this.revision=revision;
    }
    @Override public String toString(){return "RealtimeVoiceProfile[revision="+revision+"]";}
}
