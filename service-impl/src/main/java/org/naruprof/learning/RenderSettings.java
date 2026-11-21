package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class RenderSettings {
    private static final double[] SIZES = {.25,.5,.75,1,1.5,2,3,4,6,8,12};

    static ObjectNode sanitize(JsonNode input) {
        boolean world = "world".equals(text(input,"sizeUnits"));
        var regions = input.path("regions");
        var physics = input.path("physics");
        String depth = text(physics,"depthMode");
        return object("sizeUnits","world",
                "nodeSize",world ? snap(input,"nodeSize",2,SIZES) : 2,
                "edgeWidth",snap(input,"edgeWidth",1,.1,.2,.4,.6,.8,1,1.5,2,3,4,6),
                "arrowLength",world ? snap(input,"arrowLength",2,SIZES) : 2,
                "labelZoom",snap(input,"labelZoom",.5,.125,.175,.25,.35,.45,.5,.75,1,1.5,2,3),
                "labelSize",world ? snap(input,"labelSize",3,.5,.75,1,1.5,2,3,4,6,8,12,16) : 3,
                "clusterLabels",flag(input,"clusterLabels"),
                "clusterLabelSize",snap(input,"clusterLabelSize",14,7,8,9,10,12,14,16,18,20,24,28),
                "regions",object("dilation",world ? snap(regions,"dilation",20,4,5,7.5,10,15,20,30,40,60,80,120) : 20,
                        "blur",snap(regions,"blur",40,10,15,20,25,30,40,50,60,80,100,120),
                        "bitmap",snap(regions,"bitmap",256,128,192,256,384,512),
                        "edgeAlpha",snap(regions,"edgeAlpha",.2,.025,.05,.075,.1,.15,.2,.3,.4,.6,.8,1),
                        "enabled",flag(regions,"enabled")),
                "physics",object("chargeStrength",clamp(physics,"chargeStrength",-240,-480,-120),
                        "linkStrength",clamp(physics,"linkStrength",1,.25,4),
                        "linkDistance",clamp(physics,"linkDistance",40,10,160),
                        "gravity",clamp(physics,"gravity",.12,.03,.48),
                        "clusterPull",clamp(physics,"clusterPull",.15,0,1),
                        "velocityDecay",clamp(physics,"velocityDecay",.4,.18,.9),
                        "depthMode",Set.of("vertical","radial").contains(depth) ? depth : "off",
                        "depthStrength",clamp(physics,"depthStrength",.8,.2,3.2),
                        "depthSpacing",clamp(physics,"depthSpacing",60,15,240),
                        "depthBaseRadius",clamp(physics,"depthBaseRadius",220,55,880)));
    }
    private static boolean flag(JsonNode input,String field) {
        return !input.path(field).isBoolean() || input.path(field).asBoolean();
    }
    private static double number(JsonNode input,String field,double fallback) {
        var value = input.path(field);
        return value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : fallback;
    }
    private static double snap(JsonNode input,String field,double fallback,double... options) {
        double number = number(input,field,fallback), best = options[0];
        for (double candidate : options) if (Math.abs(candidate-number) < Math.abs(best-number)) best = candidate;
        return best;
    }
    private static double clamp(JsonNode input,String field,double fallback,double min,double max) {
        return Math.clamp(number(input,field,fallback),min,max);
    }
}
