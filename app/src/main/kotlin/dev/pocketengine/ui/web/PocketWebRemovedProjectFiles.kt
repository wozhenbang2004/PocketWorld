package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.workspace.RemovedProjectFiles
import dev.pocketengine.features.workspace.WorkspaceModule
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** A selected owner's deleted-file list only. Neither a generic filesystem reader nor a restore or export route. */
internal class PocketWebRemovedProjectFiles(private val controller:PocketWorldController,private val current:(String)->Boolean,
    private val window:()->PocketWebWindowTicket,private val accepts:(PocketWebWindowTicket)->Boolean){
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var serial=0L
    private var selectedIndex=-1;private var originalRows:org.json.JSONArray?=null
    private var owner="";private var project="";private var original:PocketWebWindowTicket?=null
    private var shelf:RemovedProjectFiles.Shelf?=null;private var until=0L;private var start=0;private var busy=false
    fun clear(){serial++;selectedIndex=-1;originalRows=null;owner="";project="";original=null;shelf=null;until=0;start=0;busy=false}
    fun dispatch(action:String,a:JSONObject,done:(JSONObject?,String?)->Unit){
        require(action in COMMANDS);val id=a.getString("projectId");val choice=a.getString("choice")
        require(id.matches(uuid)&&choice.matches(uuid)){"原项目或本次选择身份无效"}
        fun fields(vararg keys:String){require(a.keys().asSequence().toSet()==keys.toSet()){"删除记录操作包含未声明字段"}}
        if(action.endsWith(".close")){fields("projectId","choice");if(owner==choice&&project==id)clear();done(JSONObject().put("closed",true),null);return}
        require(current(id)){"请回到原私人项目文件列表"}
        val opening=action.endsWith(".list")&&!a.has("handle")
        if(opening){fields("projectId","choice");clear();owner=choice;project=id;original=window()}
        else check(choice==owner&&project==id&&!busy&&shelf!=null&&a.opt("handle")==shelf!!.handle&&
            original?.let(accepts)==true&&SystemClock.elapsedRealtime()<until){"原删除记录窗口已结束，请重新读取项目清单"}
        val ticket=original?:error("原项目窗口已关闭");val n=serial
        fun live()=serial==n&&current(id)&&accepts(ticket)
        fun number(k:String):Int{val v=a.opt(k);require(v is Int&&v>=0){"原清单位置无效"};return v}
        if(!opening&&action.endsWith(".list")){
            fields("projectId","choice","handle","start");val next=number("start");require(kotlin.math.abs(next-start)==20){"请从本页逐页读取原清单"}
            val page=shelf!!.page(next);selectedIndex=-1;originalRows=null;start=next;done(page.put("choice",choice),null);return
        }
        val reading=action.endsWith(".text")
        val index=if(opening)-1 else {
            if(reading)fields("projectId","choice","handle","index","version","revisionId") else fields("projectId","choice","handle","index")
            number("index").also{require(it in start until start+20){"所选文件不在这次打开的原页"}}
        }
        val selected=if(reading){
            val rows=originalRows;val version=number("version");val revision=a.getString("revisionId")
            require(index==selectedIndex&&rows!=null&&uuid.matches(revision)){"请先打开这一个删除记录的保留版本"}
            (0 until rows.length()).map{rows.getJSONObject(it)}.singleOrNull{it.opt("version")==version&&it.opt("revisionId")==revision&&it.opt("kind")=="text"&&it.opt("deleted")==false}
                ?:error("所选修订没有已核对的原文字记录")
        }else {selectedIndex=-1;originalRows=null;null}
        val old=shelf;busy=true
        try{controller.runPluginCatalogWork(Runnable{
            val result=runCatching{check(live()){"已离开原项目"};val ws=controller.nativeButlerHost().features.module("workspace") as WorkspaceModule
                if(opening){val fresh=ws.removedFilesForOwner(id,::live);fresh to fresh.page(0)}
                else if(selected!=null)null to ws.removedFileTextForOwner(old?:error("原清单已关闭"),index,selected,::live)
                else null to ws.removedFileHistoryForOwner(old?:error("原清单已关闭"),index,::live)}
            main.post{if(serial==n)busy=false
                if(!live()){done(null,"原项目窗口已变化，没有切换到其他文件");return@post}
                result.fold({(fresh,r)->if(fresh!=null){shelf=fresh;start=0;until=SystemClock.elapsedRealtime()+180_000}
                    if(!opening&&!reading){selectedIndex=index;originalRows=r.getJSONArray("versions")}
                    done(r.put("choice",choice),null)},{done(null,it.message?:"原记录未读取完整；没有修改文件")})}
        })}catch(e:Exception){if(serial==n)busy=false;done(null,e.message?:"未开始读取")}
    }
    companion object{
        private val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val COMMANDS=setOf("projects.removedFiles.list","projects.removedFiles.history","projects.removedFiles.text","projects.removedFiles.close")
    }
}
