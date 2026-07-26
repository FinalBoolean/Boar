package ac.boar.mappings.entity;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType;
import org.jetbrains.annotations.Nullable;

public interface Entity {

    long runtimeId();

    EntityDefinition definition();

    @Nullable
    Entity vehicle();

    Vector3f bedrockPosition();

    Vector3f motion();

    Vector3f bedrockRotation();

    boolean onGround();

    @Nullable
    Vector3i bedPosition();

    float bbWidth();

    float bbHeight();

    <T> void metadata(EntityDataType<T> type, T value);

    void refreshMetadata();

    void refreshAttributesToSelf();

    void releaseItem();
}
