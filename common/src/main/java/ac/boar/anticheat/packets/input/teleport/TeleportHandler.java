package ac.boar.anticheat.packets.input.teleport;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.data.input.PredictionData;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.prediction.engine.data.Vector;
import ac.boar.anticheat.teleport.data.TeleportData;
import ac.boar.anticheat.util.math.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;

import java.util.Queue;

public class TeleportHandler {
    protected void processQueuedTeleports(final BoarPlayer player, final PlayerAuthInputPacket packet) {
        final Queue<TeleportData> queuedTeleports = player.getTeleportUtil().getQueuedTeleports();

        if (queuedTeleports.isEmpty()) {
            return;
        }

        TeleportData data;
        while ((data = queuedTeleports.peek()) != null) {
            if (!data.isAccepted()) { // Teleport should be in order, which means no way the next one is accepted.
                break;
            }

            queuedTeleports.poll();

            // Bedrock don't reply to teleport individually using a separate tick packet instead it just simply set its position to
            // the teleported position and then let us know the *next tick*, so we do the same!
            this.processTeleport(player, data, packet);
        }
    }

//    private void processDimensionSwitch(final BoarPlayer player, final TeleportCache.DimensionSwitch dimension, final PlayerAuthInputPacket packet) {
//        // Dimension switch should be followed with teleport so we don't have to do resync if the position mismatch.
//        if (packet.getPosition().distance(dimension.getPosition().toVector3f()) <= 1.0E-3F) {
//            player.setPos(new Vec3(packet.getPosition().sub(0, player.getYOffset(), 0)));
//            player.unvalidatedPosition = player.prevUnvalidatedPosition = player.position.clone();
//
//            player.velocity = Vec3.ZERO.clone();
//            player.predictionResult = new PredictionData(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);
//        }
//    }

    private void processTeleport(final BoarPlayer player, final TeleportData data, final PlayerAuthInputPacket packet) {
        float distance = packet.getPosition().distance(data.getPosition().toVector3f());

        // Do this regardless if the player accept teleport or what not, we're going to force them to accept anyway.
        player.setPos(data.getPosition().down(player.getYOffset()));
        player.unvalidatedPosition = player.prevUnvalidatedPosition = player.position.clone();
        player.velocity = Vec3.ZERO.clone();
        player.predictionResult = new PredictionData(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO); // Yep!
        player.onGround = false;

        // I think I'm being a bit lenient but on Bedrock the position error seems to be a bit high.
        if (!packet.getInputData().contains(PlayerAuthInputData.HANDLE_TELEPORT) || distance > 1.0E-3F) {
            // Player rejected teleport OR this is not the latest teleport.
            if (!player.getTeleportUtil().isTeleporting()) {
                player.getTeleportUtil().teleport(data.getPosition());

                Boar.debug(player.getSession().name() + " rejected teleport with d=" + distance + ", resending teleport...", Boar.DebugMessage.INFO);
            }
        }
    }

    protected void processExempted(BoarPlayer player) {
        player.setPos(player.unvalidatedPosition);

        // Clear velocity out manually since we haven't handled em.
        player.certainVelocity = null;

        // This is fine, we only need tick end and use before and after to calculate ground.
        player.predictionResult = new PredictionData(Vec3.ZERO, player.velocity.y < 0 && player.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION) ? new Vec3(0, 1, 0) : Vec3.ZERO, player.unvalidatedTickEnd);
        player.velocity = player.unvalidatedTickEnd.clone();

        player.bestPossibility = Vector.NONE;
    }
}
