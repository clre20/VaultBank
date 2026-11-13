package clre20.vaultBank.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 從貼圖的不透明像素產生 Java 版 3D 物品模型：每一列的連續像素合併成方塊，上下相同的方塊再合併，
 * 正面與背面貼原圖，側邊貼邊緣像素的顏色，看起來像有厚度的硬幣或紙張。
 * 做法與原版平面物品相同（正面朝南、背面左右翻轉），所以物品欄、手持、掉落物的角度都和原版一致。
 */
final class ModelGenerator {

    /** 每個像素的厚度（模型單位，一格方塊是 16）。 */
    interface Thickness {
        double at(int x, int y, int size);
    }

    private record Box(int x0, int x1, int y0, int y1, double t) {
    }

    private ModelGenerator() {
    }

    static JsonObject model(BufferedImage img, String texture, Thickness thickness) {
        int size = img.getWidth();
        List<Box> boxes = boxes(img, size, thickness);
        double k = 16.0 / size;

        JsonObject root = new JsonObject();
        JsonObject textures = new JsonObject();
        textures.addProperty("0", texture);
        textures.addProperty("particle", texture);
        root.add("textures", textures);
        root.addProperty("gui_light", "front");
        JsonArray elements = new JsonArray();
        for (Box b : boxes) {
            JsonObject e = new JsonObject();
            double half = b.t() / 2;
            e.add("from", vec(b.x0() * k, 16 - (b.y1() + 1) * k, 8 - half));
            e.add("to", vec((b.x1() + 1) * k, 16 - b.y0() * k, 8 + half));
            JsonObject faces = new JsonObject();
            double u0 = b.x0() * k;
            double u1 = (b.x1() + 1) * k;
            double v0 = b.y0() * k;
            double v1 = (b.y1() + 1) * k;
            faces.add("south", face(u0, v0, u1, v1));
            faces.add("north", face(u1, v0, u0, v1));
            faces.add("up", face(u0, v0, u1, v0 + k));
            faces.add("down", face(u0, v1 - k, u1, v1));
            faces.add("west", face(u0, v0, u0 + k, v1));
            faces.add("east", face(u1 - k, v0, u1, v1));
            e.add("faces", faces);
            elements.add(e);
        }
        root.add("elements", elements);
        root.add("display", display());
        return root;
    }

    private static List<Box> boxes(BufferedImage img, int size, Thickness thickness) {
        List<Box> done = new ArrayList<>();
        List<Box> open = new ArrayList<>();
        for (int y = 0; y < size; y++) {
            List<Box> row = new ArrayList<>();
            int x = 0;
            while (x < size) {
                if (alpha(img, x, y) == 0) {
                    x++;
                    continue;
                }
                double t = thickness.at(x, y, size);
                int start = x;
                while (x + 1 < size && alpha(img, x + 1, y) != 0 && thickness.at(x + 1, y, size) == t) {
                    x++;
                }
                row.add(new Box(start, x, y, y, t));
                x++;
            }
            List<Box> next = new ArrayList<>();
            for (Box r : row) {
                Box merged = null;
                for (Box o : open) {
                    if (o.x0() == r.x0() && o.x1() == r.x1() && o.t() == r.t() && o.y1() == y - 1) {
                        merged = o;
                        break;
                    }
                }
                if (merged != null) {
                    open.remove(merged);
                    next.add(new Box(merged.x0(), merged.x1(), merged.y0(), y, merged.t()));
                } else {
                    next.add(r);
                }
            }
            done.addAll(open);
            open = next;
        }
        done.addAll(open);
        return done;
    }

    private static int alpha(BufferedImage img, int x, int y) {
        return (img.getRGB(x, y) >>> 24) & 0xff;
    }

    private static JsonArray vec(double a, double b, double c) {
        JsonArray arr = new JsonArray();
        arr.add(round(a));
        arr.add(round(b));
        arr.add(round(c));
        return arr;
    }

    private static JsonObject face(double u0, double v0, double u1, double v1) {
        JsonObject f = new JsonObject();
        JsonArray uv = new JsonArray();
        uv.add(round(u0));
        uv.add(round(v0));
        uv.add(round(u1));
        uv.add(round(v1));
        f.add("uv", uv);
        f.addProperty("texture", "#0");
        return f;
    }

    private static double round(double v) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.4f", v));
    }

    /** 與原版 item/generated 相同的顯示角度。 */
    private static JsonObject display() {
        JsonObject d = new JsonObject();
        d.add("ground", transform(new double[]{0, 0, 0}, new double[]{0, 2, 0}, 0.5));
        d.add("head", transform(new double[]{0, 180, 0}, new double[]{0, 13, 7}, 1));
        d.add("thirdperson_righthand", transform(new double[]{0, 0, 0}, new double[]{0, 3, 1}, 0.55));
        d.add("firstperson_righthand", transform(new double[]{0, -90, 25}, new double[]{1.13, 3.2, 1.13}, 0.68));
        d.add("fixed", transform(new double[]{0, 180, 0}, new double[]{0, 0, 0}, 1));
        return d;
    }

    private static JsonObject transform(double[] rot, double[] tr, double scale) {
        JsonObject o = new JsonObject();
        o.add("rotation", vec(rot[0], rot[1], rot[2]));
        o.add("translation", vec(tr[0], tr[1], tr[2]));
        o.add("scale", vec(scale, scale, scale));
        return o;
    }
}
