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
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderHandEvent;
import com.mojang.math.Axis;

@Mod("realisticclimbing")
public class RealisticClimbingMod {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "realisticclimbing");
    public static final RegistryObject<Item> ICE_AXE = ITEMS.register("ice_axe", () -> new IceAxeItem(new Item.Properties()));
    public static final String MOD_ID = "realisticclimbing";

    public static KeyMapping KEY_TOGGLE_CLIMB;
    public static KeyMapping KEY_LEFT_HAND;
    public static KeyMapping KEY_RIGHT_HAND;
    public static KeyMapping KEY_LEFT_FOOT;
    public static KeyMapping KEY_RIGHT_FOOT;

    public static boolean climbingActive = false;
    public static float stamina = 100.0f;
    public static float stability = 1.0f;

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
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
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
                        player.displayClientMessage(Component.literal("§a[Climbing] Захват стены! Q/E - Руки, Z/C - Ноги"), true);
                    } else {
                        player.displayClientMessage(Component.literal("§c[Climbing] Подойди вплотную к стене!"), true);
                    }
                } else {
                    disengageClimb(player, "§eВы отпустили стену");
                }
            }

            if (!climbingActive) {
                stamina = Math.min(100.0f, stamina + 0.4f);
                return;
            }

            handleLimbInput(player, 0, KEY_LEFT_HAND);
            handleLimbInput(player, 1, KEY_RIGHT_HAND);
            handleLimbInput(player, 2, KEY_LEFT_FOOT);
            handleLimbInput(player, 3, KEY_RIGHT_FOOT);

            // Обработка интерполяции позиций
            Vec3[] currentPositions = new Vec3[4];
            for (int i = 0; i < 4; i++) {
                LIMBS[i].currentPos = LIMBS[i].currentPos.lerp(LIMBS[i].targetPos, 0.35);
                currentPositions[i] = LIMBS[i].currentPos;
            }

            // ПРИМЕНЕНИЕ КОЛЛИЗИИ КОНЕЧНОСТЕЙ (чтобы не проходили сквозь друг друга)
            currentPositions = LimbCollision.resolveCollisions(currentPositions);
            
            int lockedCount = 0;
            int handsLocked = 0;
            Vec3 centerOfSupport = Vec3.ZERO;

            for (int i = 0; i < 4; i++) {
                Limb limb = LIMBS[i];
                limb.currentPos = currentPositions[i]; // Возвращаем позиции после коллизии

                Vec3 rootPos = getJointRoot(player, limb);
                limb.elbowOrKneePos = solveTwoBoneIK(rootPos, limb.currentPos, limb.isHand, limb.isLeft);

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

            if (lockedCount < 2 || handsLocked == 0) {
                disengageClimb(player, "§c[СРЫВ!] Потеря опоры — слишком мало точек захвата!");
                return;
            }

            centerOfSupport = centerOfSupport.scale(1.0 / lockedCount);

            stability = Mth.clamp((lockedCount / 4.0f) - (float)(player.position().distanceTo(centerOfSupport) * 0.15f), 0.0f, 1.0f);
            float drain = (4 - lockedCount) * 0.18f + 0.04f;
            stamina = Math.max(0.0f, stamina - drain);

            if (stamina <= 0.0f || stability < 0.12f) {
                disengageClimb(player, "§c[СРЫВ!] Закончились силы или потерян баланс!");
                return;
            }

            Vec3 lookDir = Vec3.directionFromRotation(0, player.getYRot());
            Vec3 idealBodyPos = centerOfSupport.subtract(lookDir.scale(0.45)).add(0, -0.85, 0);

            Vec3 sideDir = lookDir.cross(new Vec3(0, 1, 0)).normalize();
            double swayUp = (player.zza * 0.18);
            double swaySide = (-player.xxa * 0.18);
            double fatigueSway = (stamina < 35f) ? Math.sin(player.tickCount * 0.4) * 0.03 : 0.0;

            Vec3 desiredPelvis = idealBodyPos.add(sideDir.scale(swaySide)).add(0, swayUp + fatigueSway, 0);
            Vec3 velocity = desiredPelvis.subtract(player.position()).scale(0.28);

            player.setDeltaMovement(velocity);
            player.fallDistance = 0.0f;

            if (player.tickCount % 5 == 0) {
                String bar = String.format("§6Стамина: %.0f%% §7| §bБаланс: %.0f%% §7| §aОпор: %d/4",
                        stamina, stability * 100f, lockedCount);
                player.displayClientMessage(Component.literal(bar), true);
            }
        }

        private static void handleLimbInput(Player player, int index, KeyMapping key) {
            Limb limb = LIMBS[index];
            if (key.isDown()) {
                Vec3 eye = player.getEyePosition();
                Vec3 reach = eye.add(player.getLookAngle().scale(2.1));
                BlockHitResult hit = player.level().clip(new ClipContext(eye, reach, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

                if (hit.getType() == HitResult.Type.BLOCK && hit.getDirection() != Direction.DOWN) {
                    
                    // ПРОВЕРКА ЛЕДОРУБА
                    if (limb.isHand) {
                        ItemStack mainItem = player.getMainHandItem();
                        ItemStack offItem = player.getOffhandItem();
                        if (mainItem.getItem() instanceof IceAxeItem || offItem.getItem() instanceof IceAxeItem) {
                            if (!IceAxeItem.canGripSurface(player.level(), hit.getBlockPos())) {
                                return; // Ледоруб соскальзывает с песка/земли
                            }
                        }
                    }

                    Vec3 gripPoint = hit.getLocation();
                    Vec3 root = getJointRoot(player, limb);
                    if (root.distanceTo(gripPoint) <= 1.55) {
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

        private static Vec3 solveTwoBoneIK(Vec3 root, Vec3 target, boolean isHand, boolean isLeft) {
            double boneLen = isHand ? 0.42 : 0.46;
            Vec3 diff = target.subtract(root);
            double dist = Mth.clamp(diff.length(), 0.1, boneLen * 1.98);
            Vec3 midPoint = root.add(diff.scale(0.5));

            double bendAmount = Math.sqrt(Math.max(0.0, boneLen * boneLen - (dist * 0.5) * (dist * 0.5)));
            Vec3 bendDir = new Vec3(isLeft ? -0.35 : 0.35, isHand ? -0.85 : 0.4, 0.35).normalize();
            return midPoint.add(bendDir.scale(bendAmount));
        }

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

                AABB grip = new AABB(limb.currentPos, limb.currentPos).inflate(0.08);
                LevelRenderer.renderLineBox(poseStack, builder, grip.minX, grip.minY, grip.minZ, grip.maxX, grip.maxY, grip.maxZ, r, g, b, 1.0f);

                AABB joint = new AABB(limb.elbowOrKneePos, limb.elbowOrKneePos).inflate(0.05);
                LevelRenderer.renderLineBox(poseStack, builder, joint.minX, joint.minY, joint.minZ, joint.maxX, joint.maxY, joint.maxZ, 1.0f, 0.8f, 0.1f, 0.9f);
            }

            poseStack.popPose();
            buffer.endBatch(RenderType.lines());
        }

        // АНИМАЦИЯ ЛЕДОРУБА ОТ ПЕРВОГО ЛИЦА
        @SubscribeEvent
        public static void onRenderHand(RenderHandEvent event) {
            if (climbingActive) {
                if (event.getItemStack().getItem() instanceof IceAxeItem) {
                    PoseStack poseStack = event.getPoseStack();
                    poseStack.translate(0.0, 0.2, -0.6);
                    poseStack.mulPose(Axis.XP.rotationDegrees(-45f));
                    float shake = (float) Math.sin(System.currentTimeMillis() / 40.0) * 0.005f;
                    poseStack.translate(shake, shake, 0);
                }
            }
        }
    }
}
