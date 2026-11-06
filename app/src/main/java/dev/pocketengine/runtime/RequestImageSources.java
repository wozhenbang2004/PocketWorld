package dev.pocketengine.runtime;

import dev.pocketengine.core.NativeToolObservation.ProjectRequestPin;
import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/** Native project-image association for request assembly. No model, budget, history
 * rewrite, pixel capture or admission grant; the original run owns every pin. */
final class RequestImageSources {
    private RequestImageSources() { }

    static void prune(List<ProjectRequestPin> pins,JSONArray history)throws Exception {
        if(history!=null)requireProjection(pins,history);
        for(var iterator=pins.iterator();iterator.hasNext();){var pin=iterator.next();if(history==null||!pin.presentIn(history)){pin.close();iterator.remove();}}
    }

    static void requireProjection(List<ProjectRequestPin> pins,JSONArray messages)throws Exception {
        for(var pin:pins)if(!pin.presentIn(messages)&&pin.includesBytes(messages)){
            boolean covered=false;
            for(var current:pins)if(current!=pin&&pin.sameNativeBytes(current)&&current.presentIn(messages)){covered=true;break;}
            if(!covered)throw new SecurityException("工程图片保留了像素却丢失原生观察关联，未派发此请求");
        }
    }

    /** Call after the projection check. This immutable selection borrows pins and
     * retains the actual request array, not a copy that could hide later mutation. */
    static Request select(List<ProjectRequestPin> pins,JSONArray actualMessages)throws Exception {
        List<ProjectRequestPin> selected=new ArrayList<>();
        for(var image:pins)if(image.presentIn(actualMessages))selected.add(image);
        return new Request(selected,actualMessages);
    }

    static final class Request {
        private final List<ProjectRequestPin> pins;
        private final JSONArray actualMessages;
        private Request(List<ProjectRequestPin> pins,JSONArray messages){this.pins=List.copyOf(pins);this.actualMessages=messages;}
        boolean hasImages(){return !pins.isEmpty();}
        /** Every physical attempt must recheck. A successful prior check is not cached
         * authority; source revocation, cancellation and pause revisions remain live. */
        void requireCurrent(RunControl original)throws Exception {
            requireProjection(pins,actualMessages);
            for(var image:pins){if(!image.presentIn(actualMessages))throw new SecurityException("原工程图片请求内容已变化");image.requireCurrent(original);}
        }
    }
}
