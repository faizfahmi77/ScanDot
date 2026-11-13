import com.roomscan.app.CloudDataKt;
import com.roomscan.app.CloudGeometry;
import com.roomscan.app.CloudGeometryKt;
import com.roomscan.app.CloudPreset;
import com.roomscan.app.CloudRotation;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Exports the app's actual Kotlin parser and fit/projection to the off-device GL check. */
public final class CloudPreview {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("CloudPreview input.json output.bin FRONT|SIDE|TOP width height");
        }
        CloudGeometry geometry = CloudDataKt.parseCloud(new JSONObject(Files.readString(Path.of(args[0]))));
        CloudPreset preset = CloudPreset.valueOf(args[2]);
        CloudRotation rotation = new CloudRotation(preset.getYaw(), preset.getPitch(), 0f);
        float aspect = Float.parseFloat(args[3]) / Float.parseFloat(args[4]);
        float[] projection = new float[16];
        CloudGeometryKt.cloudProjection(rotation, CloudGeometryKt.fitCloud(geometry, rotation, aspect), aspect,
            1f, 0f, 0f, projection);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[1]))))) {
            output.writeInt(geometry.getPointCount());
            for (float value : projection) output.writeFloat(value);
            for (float value : geometry.getVertices()) output.writeFloat(value);
        }
        System.out.printf("Parsed/fitted %d of %d points with the app's Kotlin code (%s).%n",
            geometry.getPointCount(), geometry.getTotalPoints(), preset);
    }
}
