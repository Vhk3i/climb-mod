package com.realisticclimbing;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.lwjgl.glfw.GLFW;

@Mod("realisticclimbing")
public class RealisticClimbingMod {
    public static final String MOD_ID = "realisticclimbing";

    // Клавиши управления 4 отдельными конечностями и режимом скалолазания
    public static KeyMapping KEY_TOGGLE_CLIMB;
    public static KeyMapping KEY_LEFT_HAND;   // Q - переставить левую руку туда, куда смотришь
    public static KeyMapping KEY_RIGHT_HAND;  // E - переставить правую руку
    public static KeyMapping KEY_LEFT_FOOT;   // Z - переставить левую ногу
    public static KeyMapping KEY_RIGHT_FOOT;  // C - переставить правую ногу

    public static boolean climbingActive = false;
    public static float stamina = 100.0f;
    public static float stability = 1.0f;

    // 4 независимые конечности: 0 - Л.Рука, 1 - П.Рука, 2 - Л.Нога, 3 - П.Нога
    public static class Limb {
        public final String name;
        public final boolean isHand;
        public final boolean isLeft;
        public boolean locked = false;
        public Vec3 currentPos = Vec3.ZERO;
        public Vec3 targetPos = Vec3.ZERO;
        public Vec3 elbowOrKneePos = Vec3.ZERO;
        public float gripQuality = 1.0f;

        public Limb(String name, boolean isHand, boolean isLeft) {
            this.name = name;
            this.isHand = isHand;
            this.isLeft = isLeft;
        }
    }

    public static final Limb[] LIMBS = new Limb[]{
            new Limb("Левая рука", true, true),
            new Limb("Правая рука", true, false),
            new Limb("Левая нога", false, true),
            new Limb("Правая нога", false, false)
    };

    public RealisticClimbingMod() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientEvents::registerKeys);
            MinecraftForge.EVENT_BUS.register(ClientEvents.class);
        }
    }

    public static class ClientEvents {
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            KEY_TOGGLE_CLIMB = new KeyMapping("Вкл/Выкл Скалолазание", GLFW.GLFW_KEY_R, "Realistic Climbing");
            KEY_LEFT_HAND = new KeyMapping("Перехват: Левая рука", GLFW.GLFW_KEY_Q, "Realistic Climbing");
            KEY_RIGHT_HAND = new KeyMapping("Перехват: Правая рука", GLFW.GLFW_KEY_E, "Realistic Climbing");
            KEY_LEFT_FOOT = new KeyMapping("Опора: Левая нога", GLFW.GLFW_KEY_Z, "Realistic Climbing");
            KEY_RIGHT_FOOT = new KeyMapping("Опора: Правая нога", GLFW.GLFW_KEY_C, "Realistic Climbing");

            event.register(KEY_TOGGLE_CLIMB);
            event.register(KEY_LEFT_HAND);
            event.register(KEY_RIGHT_HAND);
            event.register(KEY_LEFT_FOOT);
            event.register(KEY_RIGHT_FOOT);
        }

        @SubscribeEvent
        public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Player player = event.player;
            Minecraft mc = Minecraft.getInstance();
            if (player != mc.player) return;

            while (KEY_TOGGLE_CLIMB.consumeClick()) {
                if (!climbingActive) {
                    if (tryEngageWall(player)) {
                        climbingActive = true;
                        player.displayClientMessage(Component.literal("§a[Climbing] Захват стены! Управление конечностями: Q (Л.Рука), E (П.Рука), Z (Л.Нога), C (П.Нога)"), true);
                    } else {
                        player.displayClientMessage(Component.literal("§c[Climbing] Подойди вплотную к стене!"), true);
                    }
                } else {
                    disengageClimb(player, "Вы отпустили стену");
                }
            }

            if (!climbingActive) {
                stamina = Math.min(100.0f, stamina + 0.4f);
                return;
            }

            // Обработка независимого перемещения конечностей по взгляду игрока (Raycast до выступа)
            handleLimbInput(player, 0, KEY_LEFT_HAND);
            handleLimbInput(player, 1, KEY_RIGHT_HAND);
            handleLimbInput(player, 2, KEY_LEFT_FOOT);
            handleLimbInput(player, 3, KEY_RIGHT_FOOT);

            // Плавная интерполяция движения конечностей и расчёт двухзвенного IK (локти и колени)
            int lockedCount = 0;
            int handsLocked = 0;
            Vec3 centerOfSupport = Vec3.ZERO;

            for (int i = 0; i < 4; i++) {
                Limb limb = LIMBS[i];
                limb.currentPos = limb.currentPos.lerp(limb.targetPos, 0.35);

                // Расчёт корневого сустава (плечо или бедро)
                Vec3 rootPos = getJointRoot(player, limb);
                // Двухзвенный IK: вычисляем положение локтя или колена с естественным сгибом
                limb.elbowOrKneePos = solveTwoBoneIK(rootPos, limb.currentPos, limb.isHand, limb.isLeft);

                // Если конечность растянулась слишком далеко (> 1.65 блока), она срывается с зацепа
                if (limb.locked && rootPos.distanceTo(limb.currentPos) > 1.65) {
                    limb.locked = false;
                    player.displayClientMessage(Component.literal("§e[!] " + limb.name + " сорвалась из-за натяжения!"), true);
                }

                if (limb.locked) {
                    lockedCount++;
                    if (limb.isHand) handsLocked++;
                    centerOfSupport = centerOfSupport.add(limb.currentPos);
                }
            }

            // Проверка потери равновесия и срыва
            if (lockedCount < 2 || handsLocked == 0) {
                disengageClimb(player, "§c[СРЫВ!] Потеря опоры — слишком мало точек захвата!");
                return;
            }

            centerOfSupport = centerOfSupport.scale(1.0 / lockedCount);

            // Расчёт баланса и выносливости
            stability = Mth.clamp((lockedCount / 4.0f) - (float)(player.position().distanceTo(centerOfSupport) * 0.15f), 0.0f, 1.0f);
            float drain = (4 - lockedCount) * 0.18f + 0.04f;
            stamina = Math.max(0.0f, stamina - drain);

            if (stamina <= 0.0f || stability < 0.12f) {
                disengageClimb(player, "§c[СРЫВ!] Закончились силы или потерян баланс!");
                return;
            }

            // Физика тела: притягивание центра тяжести (таза) к среднему полигону зацепов конечностей
            Vec3 lookDir = Vec3.directionFromRotation(0, player.getYRot());
            Vec3 idealBodyPos = centerOfSupport.subtract(lookDir.scale(0.45)).add(0, -0.85, 0);

            // Смещение корпуса клавишами WASD (перенос веса тела без перехвата рук)
            Vec3 sideDir = lookDir.cross(new Vec3(0, 1, 0)).normalize();
            double swayUp = (player.zza * 0.18);
            double swaySide = (-player.xxa * 0.18);

            // Небольшое физическое покачивание тела при усталости
            double fatigueSway = (stamina < 35f) ? Math.sin(player.tickCount * 0.4) * 0.03 : 0.0;

            Vec3 desiredPelvis = idealBodyPos.add(sideDir.scale(swaySide)).add(0, swayUp + fatigueSway, 0);
            Vec3 velocity = desiredPelvis.subtract(player.position()).scale(0.28);

            player.setDeltaMovement(velocity);
            player.fallDistance = 0.0f;

            // Отображение телеметрии (Стамина и Баланс) над хотбаром
            if (player.tickCount % 5 == 0) {
                String bar = String.format("§6Стамина: %.0f%% §7| §bБаланс: %.0f%% §7| §aОпор: %d/4",
                        stamina, stability * 100f, lockedCount);
                player.displayClientMessage(Component.literal(bar), true);
            }
        }

        private static void handleLimbInput(Player player, int index, KeyMapping key) {
            Limb limb = LIMBS[index];
            if (key.isDown()) {
                // Ищем точку захвата на стене по направлению взгляда
                Vec3 eye = player.getEyePosition();
                Vec3 reach = eye.add(player.getLookAngle().scale(2.1));
                BlockHitResult hit = player.level().clip(new ClipContext(eye, reach, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

                if (hit.getType() == HitResult.Type.BLOCK && hit.getDirection() != Direction.DOWN) {
                    Vec3 gripPoint = hit.getLocation();
                    Vec3 root = getJointRoot(player, limb);
                    if (root.distanceTo(gripPoint) <= 1.55) {
                        limb.locked = false; // В процессе переноса конечность не держит вес
                        limb.targetPos = gripPoint;
                        BlockState state = player.level().getBlockState(hit.getBlockPos());
                        limb.gripQuality = state.getBlock().getFriction();
                        limb.locked = true;
                    }
                }
            }
        }

        private static boolean tryEngageWall(Player player) {
            Vec3 eye = player.getEyePosition();
            Vec3 forward = Vec3.directionFromRotation(0, player.getYRot());
            BlockHitResult hit = player.level().clip(new ClipContext(eye, eye.add(forward.scale(1.4)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

            if (hit.getType() != HitResult.Type.BLOCK) return false;

            Vec3 wallHit = hit.getLocation();
            Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize();

            // Расставляем 4 конечности по стартовым точкам захвата на стене
            LIMBS[0].targetPos = LIMBS[0].currentPos = wallHit.add(right.scale(-0.35)).add(0, 0.25, 0);
            LIMBS[1].targetPos = LIMBS[1].currentPos = wallHit.add(right.scale(0.35)).add(0, 0.25, 0);
            LIMBS[2].targetPos = LIMBS[2].currentPos = wallHit.add(right.scale(-0.25)).add(0, -0.75, 0);
            LIMBS[3].targetPos = LIMBS[3].currentPos = wallHit.add(right.scale(0.25)).add(0, -0.75, 0);

            for (Limb limb : LIMBS) {
                limb.locked = true;
            }
            return true;
        }

        private static void disengageClimb(Player player, String reason) {
            climbingActive = false;
            for (Limb limb : LIMBS) limb.locked = false;
            player.displayClientMessage(Component.literal(reason), true);
        }

        private static Vec3 getJointRoot(Player player, Limb limb) {
            Vec3 forward = Vec3.directionFromRotation(0, player.getYRot());
            Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize();
            double sideOffset = limb.isLeft ? -0.28 : 0.28;
            double heightOffset = limb.isHand ? 1.32 : 0.72;
            return player.position().add(right.scale(sideOffset)).add(0, heightOffset, 0);
        }

        // Двухзвенный аналитический IK-солвер: вычисляет 3D-координату сгиба локтя/колена
        private static Vec3 solveTwoBoneIK(Vec3 root, Vec3 target, boolean isHand, boolean isLeft) {
            double boneLen = isHand ? 0.42 : 0.46;
            Vec3 diff = target.subtract(root);
            double dist = Mth.clamp(diff.length(), 0.1, boneLen * 1.98);
            Vec3 midPoint = root.add(diff.scale(0.5));

            double bendAmount = Math.sqrt(Math.max(0.0, boneLen * boneLen - (dist * 0.5) * (dist * 0.5)));
            // Локти сгибаются наружу и вниз, колени — вперёд и чуть вбок
            Vec3 bendDir = new Vec3(isLeft ? -0.35 : 0.35, isHand ? -0.85 : 0.4, 0.35).normalize();
            return midPoint.add(bendDir.scale(bendAmount));
        }

        // Применение углов IK к 3D-модели персонажа от третьего лица (F5)
        @SubscribeEvent
        public static void onRenderPlayerPost(RenderPlayerEvent.Pre event) {
            if (!climbingActive || event.getEntity() != Minecraft.getInstance().player) return;
            PlayerModel<AbstractClientPlayer> model = event.getRenderer().getModel();

            applyLimbRotationToModel(event.getEntity(), LIMBS[0], model.leftArm);
            applyLimbRotationToModel(event.getEntity(), LIMBS[1], model.rightArm);
            applyLimbRotationToModel(event.getEntity(), LIMBS[2], model.leftLeg);
            applyLimbRotationToModel(event.getEntity(), LIMBS[3], model.rightLeg);
        }

        private static void applyLimbRotationToModel(Player player, Limb limb, net.minecraft.client.model.geom.ModelPart part) {
            Vec3 root = getJointRoot(player, limb);
            Vec3 dir = limb.currentPos.subtract(root).normalize();
            part.xRot = (float) -Math.asin(Mth.clamp(dir.y, -1.0, 1.0)) - 1.2f;
            part.zRot = (float) (limb.isLeft ? -0.25f : 0.25f);
        }

        // Отрисовка 3D-точек захвата (зацепов) и сегментов IK-суставов в реальном времени
        @SubscribeEvent
        public static void onRenderWorld(RenderLevelStageEvent event) {
            if (!climbingActive || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player == null) return;

            Vec3 cam = event.getCamera().getPosition();
            PoseStack poseStack = event.getPoseStack();
            MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
            VertexConsumer builder = buffer.getBuffer(RenderType.lines());

            poseStack.pushPose();
            poseStack.translate(-cam.x, -cam.y, -cam.z);

            for (Limb limb : LIMBS) {
                float r = limb.isHand ? 0.1f : 0.2f;
                float g = limb.locked ? 0.95f : 0.2f;
                float b = limb.isHand ? 0.95f : 0.3f;

                // Бокс точки захвата на стене
                AABB gripBox = new AABB(limb.currentPos, limb.currentPos).inflate(0.08);
                LevelRenderer.renderLineBox(poseStack, builder, gripBox, r, g, b, 1.0f);

                // Отрисовка двухзвенного сустава (Плечо -> Локоть -> Кисть)
                AABB jointBox = new AABB(limb.elbowOrKneePos, limb.elbowOrKneePos).inflate(0.05);
                LevelRenderer.renderLineBox(poseStack, builder, jointBox, 1.0f, 0.8f, 0.1f, 0.9f);
            }

            poseStack.popPose();
            buffer.endBatch(RenderType.lines());
        }
    }
}
