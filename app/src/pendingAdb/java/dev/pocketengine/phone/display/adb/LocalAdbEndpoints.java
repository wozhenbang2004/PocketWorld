package dev.pocketengine.phone.display.adb;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

/** Local-device endpoint policy, deliberately without arbitrary DNS or LAN targets. */
public final class LocalAdbEndpoints {
    public static final String PAIRING="_adb-tls-pairing._tcp.";
    public static final String CONNECT="_adb-tls-connect._tcp.";
    private final List<byte[]> addresses;
    public LocalAdbEndpoints(List<InetAddress> local){
        addresses=new ArrayList<>();for(InetAddress address:local)if(!address.isAnyLocalAddress())addresses.add(address.getAddress().clone());
    }
    public static LocalAdbEndpoints current()throws Exception {
        List<InetAddress> local=new ArrayList<>();local.add(InetAddress.getByName("127.0.0.1"));local.add(InetAddress.getByName("::1"));
        Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
        if(interfaces!=null)while(interfaces.hasMoreElements()){
            NetworkInterface network=interfaces.nextElement();if(!network.isUp())continue;
            Enumeration<InetAddress> values=network.getInetAddresses();while(values.hasMoreElements())local.add(values.nextElement());
        }
        return new LocalAdbEndpoints(local);
    }
    public String check(String input,int port)throws Exception {
        if(port<1||port>65535)throw new IllegalArgumentException("请输入系统无线调试页面的有效端口");
        if(input==null||input.length()>160)throw new SecurityException("仅支持本机无线调试地址");
        String host=input.trim();if(host.equals("localhost"))host="127.0.0.1";
        if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        if(host.contains(":")){
            String[] parts=host.split("%",-1);if(parts.length>2||!parts[0].matches("[0-9a-fA-F:.]+")||parts.length==2&&!parts[1].matches("[A-Za-z0-9_.-]{1,64}"))throw new SecurityException("无线ADB不解析外部主机名");
        }else if(!host.matches("[0-9.]+"))throw new SecurityException("无线ADB不解析外部主机名");
        InetAddress address=InetAddress.getByName(host);if(address.isAnyLocalAddress())throw new SecurityException("不能把通配监听地址用作ADB目标");
        for(byte[] local:addresses)if(Arrays.equals(local,address.getAddress()))return address.getHostAddress();
        throw new SecurityException("目标不是当前手机本机地址，未连接任意局域网设备");
    }
    public static String serviceType(String input){
        if(input==null)throw new IllegalArgumentException("没有ADB服务类型");String type=input.endsWith(".")?input:input+".";
        if(!PAIRING.equals(type)&&!CONNECT.equals(type))throw new SecurityException("只接受无线调试TLS配对与连接服务");return type;
    }
    public static String pairingCode(String input){if(input==null||!input.matches("[0-9]{6}"))throw new IllegalArgumentException("配对码需为系统当前显示的六位数字");return input;}
    public static void distinctPorts(int pairing,int connecting){if(pairing>0&&pairing==connecting)throw new IllegalArgumentException("配对端口与连接端口不同，请分别读取系统页面");}
}
