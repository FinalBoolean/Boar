package ac.boar.anticheat.packets.other;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.ack.types.VehicleClearAck;
import ac.boar.anticheat.ack.types.VehicleSetAck;
import ac.boar.anticheat.compensated.cache.entity.EntityCache;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.data.VehicleData;
import ac.boar.anticheat.util.MathUtil;
import ac.boar.mappings.entity.Entity;
import ac.boar.protocol.api.CloudburstPacketEvent;
import ac.boar.protocol.api.PacketListener;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PredictionType;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;

public class VehiclePackets implements PacketListener {
    private static final float HARD_POSITION_ERROR_SQUARED = 1.0F;
    private static final long CORRECTION_INTERVAL_TICKS = 2;

    @Override
    public void onPacketReceived(CloudburstPacketEvent event) {
        final BoarPlayer player = event.getPlayer();

        if (event.getPacket() instanceof PlayerAuthInputPacket packet
                && Boar.getConfig().forceServerAuthoritativeVehicles()) {
            final boolean wasClientPredicted = packet.getInputData().remove(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);
            final Entity vehicle = player.getEntity().vehicle();

            // Bedrock 26.10+ marks boats and other controllable mounts as client-predicted.
            // Geyser normally trusts the position included in this packet when the marker is
            // present. Removing the marker makes Geyser run its Java vehicle simulator and send
            // the resulting movement to both endpoints.
            if (vehicle == null) {
                return;
            }

            final VehicleData data = matchingVehicleData(player, vehicle);
            if (data != null && !data.clientPredictionSuppressed) {
                data.clientPredictionSuppressed = true;
                Boar.debug("[vehicle-debug] enabled server-authoritative vehicle mode runtimeId="
                        + data.vehicleRuntimeId + " tick=" + packet.getTick()
                        + " clientPredicted=" + wasClientPredicted, Boar.DebugMessage.INFO);
            }
            if (data == null && !wasClientPredicted) {
                return;
            }

            final Vector3f clientPosition = packet.getPosition();
            final Vector3f clientDelta = packet.getDelta();
            final Vector2f clientRotation = packet.getVehicleRotation();
            final Vector3f authoritativePosition = vehicle.bedrockPosition();
            final Vector3f authoritativeDelta = vehicle.motion();

            if (data != null) {
                data.latestClientTick = packet.getTick();
                data.clientVehiclePosition = clientPosition;
                data.clientVehicleDelta = clientDelta;
                data.clientVehicleRotation = clientRotation;

                final float positionError = distanceSquared(clientPosition, authoritativePosition);
                final boolean missingAuthoritativeUpdate =
                        data.lastAuthoritativeUpdateTick < packet.getTick() - 1;

                // Moving vehicles normally correct from their outbound authoritative update,
                // after Geyser has simulated the tick. Some vehicles do not emit a movement
                // packet while stationary, so fall back to the current authoritative snapshot.
                // This also makes live corrections independent of what position the client
                // chooses to report.
                if (positionError >= HARD_POSITION_ERROR_SQUARED || missingAuthoritativeUpdate) {
                    queueVehicleCorrection(event, player, data, vehicle, authoritativePosition,
                            authoritativeDelta, vehicle.bedrockRotation(), 0.0F,
                            positionError >= HARD_POSITION_ERROR_SQUARED);
                }
            }

            // Boar and Geyser must never carry the client's predicted mounted coordinates into
            // the eventual dismount. Apply the authoritative snapshot both now (for Boar's
            // bookkeeping) and after the remaining listeners (for the packet Geyser receives).
            packet.setPosition(authoritativePosition);
            packet.setDelta(authoritativeDelta);
            event.getPostTasks().add(() -> {
                packet.setPosition(authoritativePosition);
                packet.setDelta(authoritativeDelta);
            });
        }
    }

    @Override
    public void onPacketSend(CloudburstPacketEvent event) {
        final BoarPlayer player = event.getPlayer();

        if (event.getPacket() instanceof MoveEntityDeltaPacket packet
                && Boar.getConfig().forceServerAuthoritativeVehicles()) {
            final VehicleData data = player.vehicleData;
            if (data != null && data.clientPredictionSuppressed
                    && packet.getRuntimeEntityId() == data.vehicleRuntimeId) {
                final Entity vehicle = player.getEntity().vehicle();
                if (vehicle != null && vehicle.runtimeId() == data.vehicleRuntimeId) {
                    trackAndCorrectVehicle(event, player, data, vehicle);
                }
            }
        }

        if (event.getPacket() instanceof SetEntityLinkPacket packet) {
            final EntityLinkData link = packet.getEntityLink();
            if (link == null) {
                return;
            }

            long entityId = packet.getEntityLink().getFrom();
            long riderId = packet.getEntityLink().getTo();

            // We handle this separately.
            if (riderId != player.runtimeEntityId) {
                final EntityCache riderCache = player.compensatedWorld.getEntity(riderId);
                if (riderCache != null) {
                    riderCache.setInVehicle(link.getType() != EntityLinkData.Type.REMOVE);
                }

                return;
            }

            final EntityCache cache = player.compensatedWorld.getEntity(entityId);
            if (cache == null) {
                // Likely won't happen, but why not!
                return;
            }

            // Yep.
            player.getTeleportUtil().getQueuedTeleports().clear();

            if (link.getType() == EntityLinkData.Type.REMOVE) {
                player.queueAcknowledgment(new VehicleClearAck());
                return;
            }

            player.queueAcknowledgment(new VehicleSetAck(entityId));
        }
    }

    private static void trackAndCorrectVehicle(CloudburstPacketEvent event, BoarPlayer player,
                                               VehicleData data, Entity vehicle) {
        final Vector3f position = vehicle.bedrockPosition();
        final Vector3f rotation = vehicle.bedrockRotation();
        final Vector3f delta = data.lastAuthoritativePosition == null
                ? vehicle.motion()
                : position.sub(data.lastAuthoritativePosition);
        final float angularVelocity = data.lastAuthoritativeRotation == null
                ? 0.0F
                : MathUtil.wrapDegrees(rotation.getY() - data.lastAuthoritativeRotation.getY());

        data.lastAuthoritativePosition = position;
        data.lastAuthoritativeRotation = rotation;
        data.lastAuthoritativeUpdateTick = data.latestClientTick;

        final float positionError = data.clientVehiclePosition == null
                ? 0.0F
                : distanceSquared(data.clientVehiclePosition, position);

        queueVehicleCorrection(event, player, data, vehicle, position, delta, rotation,
                angularVelocity, positionError >= HARD_POSITION_ERROR_SQUARED);
    }

    private static void queueVehicleCorrection(CloudburstPacketEvent event, BoarPlayer player,
                                               VehicleData data, Entity vehicle, Vector3f position,
                                               Vector3f delta, Vector3f rotation, float angularVelocity,
                                               boolean hardCorrection) {
        final long tick = data.latestClientTick;
        if (tick < 0 || tick <= data.lastCorrectionTick) {
            return;
        }
        if (!hardCorrection && data.lastCorrectionTick >= 0
                && tick - data.lastCorrectionTick < CORRECTION_INTERVAL_TICKS) {
            return;
        }

        final CorrectPlayerMovePredictionPacket correction = new CorrectPlayerMovePredictionPacket();
        correction.setPosition(position);
        correction.setDelta(delta);
        correction.setOnGround(vehicle.onGround());
        correction.setTick(tick);
        correction.setPredictionType(PredictionType.VEHICLE);
        correction.setVehicleRotation(Vector2f.from(rotation.getX(), rotation.getY()));
        correction.setVehicleAngularVelocity(angularVelocity);

        data.lastCorrectionTick = tick;
        if (!data.correctionStarted) {
            data.correctionStarted = true;
            Boar.debug("[vehicle-debug] started live vehicle corrections runtimeId="
                    + data.vehicleRuntimeId + " tick=" + tick, Boar.DebugMessage.INFO);
        }
        event.getPostTasks().add(() -> player.getConnection().sendPacketImmediately(correction));
    }

    private static VehicleData matchingVehicleData(BoarPlayer player, Entity vehicle) {
        final VehicleData data = player.vehicleData;
        return data != null && data.vehicleRuntimeId == vehicle.runtimeId() ? data : null;
    }

    private static float distanceSquared(Vector3f first, Vector3f second) {
        return first.sub(second).lengthSquared();
    }
}
