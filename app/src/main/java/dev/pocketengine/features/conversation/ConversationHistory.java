package dev.pocketengine.features.conversation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Title search and date organization over loaded persisted conversations; no second history store. */
public final class ConversationHistory {
    public static final class Item {
        public final String id,title;
        public final long updatedAt;
        public Item(String id,String title,long updatedAt){this.id=id;this.title=title;this.updatedAt=updatedAt;}
    }
    public static final class Group {
        public final String label;
        public final List<Item> items;
        Group(String label,List<Item> items){this.label=label;this.items=List.copyOf(items);}
    }
    private ConversationHistory(){}
    public static List<Group> organize(List<Item> loaded,String query,long now,ZoneId zone){
        String needle=query==null?"":query.strip().toLowerCase(Locale.ROOT);LinkedHashMap<String,Item> unique=new LinkedHashMap<>();
        for(Item item:loaded){if(item==null||item.id==null||item.id.isBlank()||item.title==null)continue;Item prior=unique.get(item.id);if(prior==null||item.updatedAt>prior.updatedAt)unique.put(item.id,item);}
        List<Item> visible=new ArrayList<>();for(Item item:unique.values())if(item.title.toLowerCase(Locale.ROOT).contains(needle))visible.add(item);
        visible.sort(Comparator.comparingLong((Item item)->item.updatedAt).reversed().thenComparing(item->item.id));LocalDate today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();LinkedHashMap<String,List<Item>> grouped=new LinkedHashMap<>();
        for(Item item:visible){String label="时间未记录";if(item.updatedAt>0){long age=ChronoUnit.DAYS.between(Instant.ofEpochMilli(item.updatedAt).atZone(zone).toLocalDate(),today);label=age==0?"今天":age>0&&age<=7?"最近七天":age<0?"其他日期":"更早";}grouped.computeIfAbsent(label,key->new ArrayList<>()).add(item);}
        List<Group> groups=new ArrayList<>();grouped.forEach((label,items)->groups.add(new Group(label,items)));return List.copyOf(groups);
    }
}
