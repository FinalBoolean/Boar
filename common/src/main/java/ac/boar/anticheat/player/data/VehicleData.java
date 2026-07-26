package ac.boar.anticheat.player.data;

import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;

public class VehicleData {
    public boolean canWeControlThisVehicle;
    public boolean clientPredictionSuppressed;
    public long vehicleRuntimeId;

    public long latestClientTick = -1;
    public long lastCorrectionTick = -1;
    public long lastAuthoritativeUpdateTick = -1;
    public boolean correctionStarted;
    public Vector3f clientVehiclePosition;
    public Vector3f clientVehicleDelta;
    public Vector2f clientVehicleRotation;
    public Vector3f lastAuthoritativePosition;
    public Vector3f lastAuthoritativeRotation;
}
