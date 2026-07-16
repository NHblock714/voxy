# voxy × Create 远景渲染联动 — 经验与架构总册

面向"把 Create 的运动结构在 voxy LOD 距离正确显示/剔除"这一类工作。列车是第一个完整案例;本册沉淀其渲染路径地图、剔除挂点分类学、踩坑,并据此规划**动态结构(通用 contraption)联动**。

修改本联动前必读。代码在 `client/compat/create/`(自绘 LOD)+ `client/mixin/create/`(剔除挂点)+ `commonImpl/compat/create/`(协议/服务端采样)。

---

## 0. 一句话结论

Create 一个"东西"往往有**多条并存的渲染路径**,分散在 Flywheel 实例化、Flywheel embedding、主 context、vanilla EntityRenderer、vanilla BER 五个层面。**在 voxy LOD 环境里让它正确显隐,必须逐条路径挂剔除,漏一条就浮空/残留。** 判断走哪条路径只能靠反编译,不能凭直觉。

---

## 1. 渲染路径地图(Create 6.0.10 + Flywheel 1.0.6,实测)

### 1.1 轨道(track)
- **弯道 bezier**:`AllBlockEntityTypes.TRACK` 注册**双渲染器** `.visual(TrackVisual)` + `.renderer(TrackRenderer)`,**互斥**:
  - Flywheel backend **ON**(默认/iris+colorwheel):`TrackVisual`(GPU 实例,`BezierTrackVisual` 的 `TransformedInstance[]`),`renderSafe` 内 `if(!supportsVisualization) return` 直接跳过。
  - backend **OFF**:vanilla `TrackRenderer.renderSafe`(`shouldRenderOffScreen=true`/`getViewDistance=192`)。
- **直线轨道**:实体方块,vanilla 区块 mesh(视距内)。视距外我们自绘(`DistantTrackRenderer.bakeSection`)。

### 1.2 火车(carriage contraption)
一列车 = 多节 `CarriageContraptionEntity`(`AbstractContraptionEntity` 子类),每节多条路径:
- **车体 structure + 有 visualizer 的子 BE + 有 createVisual 的 actor**:Flywheel `CarriageContraptionVisual` → **`VisualEmbedding`**(整体一个 embedding pose 定位)。
- **转向架 bogey**:Flywheel `BogeyVisual`,用**主 `visualizationContext`**(世界坐标独立实例),**不经 embedding**。
- **vanilla `ContraptionEntityRenderer.render`**:**每帧照跑**(contraption 的 entity visual 注册为 `renderNormally=true` → Flywheel 的 `skipVanillaRender==false` → 不 cancel vanilla renderEntity)。跑三 pass:
  1. structure SBB — Flywheel on 时 gated skip。
  2. `renderBlockEntities` — 没 Flywheel visualizer 的子 BE。
  3. `renderActors` → `MovementBehaviour.renderInContraption` — **controls(无 BE,纯 actor)、blaze_burner(`disableBlockEntityRendering()==true` 排除出 BE 列表)** 等"组装变形态"方块。
  - 子类 `CarriageContraptionEntityRenderer.render` 在 `super.render` 后还画 bogey(Flywheel on 时 gated skip)。

### 1.3 站点(station)
- `create:track_station` 灰箭头 = `StationRenderer`(纯 vanilla BER,`shouldRenderOffScreen=true`/`getViewDistance=192`,**无 Flywheel visual**)。

### 1.4 关键环境变量
- **colorwheel** = iris 下的整套 Flywheel 引擎 `ClrwlEngine`(强制 iris 下 Flywheel ON;`ClrwlEngine`/`ClrwlBackend`/`ClrwlDrawManager`/`EmbeddedEnvironment`)。用户跑它,所以火车/轨道全走实例化路径,且引擎是 ClrwlEngine 非默认 EngineImpl → 剔除挂点必须**引擎无关**(Flywheel API 层)。
- **nowheel** = tr7zw EntityCulling 的 Create 集成(遮挡剔除;`FlywheelVisualToggleListener` 按遮挡 queueAdd/queueRemove visual)。**遮挡判据在 LOD 区失效**(vanilla 无真实方块遮挡 → 判可见不剔),这是浮空的现实诱因,voxy 保活区块放大之。
- voxy LOD 深度体系:vanilla 覆盖像素在 `initDepthStencil` 重投影真实深度(非哨兵),stencil 标记网格,详见 PROGRESS 2026-07-13 段。

---

## 2. 剔除挂点分类学(按渲染路径选挂法)

| 渲染路径 | 挂点 | 手法 | 引擎无关? |
|---|---|---|---|
| Flywheel 实例 visual(TrackVisual) | 令其 `SimpleDynamicVisual`,`beginFrame` | 超距离 `_delete()` 实例、回来重建 | 是(planFrame 两引擎都驱动) |
| Flywheel embedding(车体) | `beginFrame` **TAIL**(不 cancel) | `embedding.transforms(零矩阵)` 塌缩;**不能 HEAD+cancel**(跳过引擎每帧 setup,embedding 掉出 flush) | 是 |
| 主 context 子 visual(bogey) | 同一 `beginFrame` TAIL | 遍历 `visuals[]` 调 `BogeyVisual.hide()` | 是 |
| vanilla EntityRenderer(contraption 三 pass) | `ContraptionEntityRenderer.render` HEAD cancellable | 超距离 `ci.cancel()` | 引擎无关(vanilla) |
| vanilla BER(track/station/kinetic) | `renderSafe` HEAD cancellable | 超距离 `ci.cancel()` | 引擎无关 |
| Flywheel kinetic BE(shaft/cog/机器) | 基类 `KineticBlockEntityVisual` 令 `SimpleDynamicVisual`.beginFrame(未 override 者);override beginFrame 的 16 机器多目标 `@Inject` HEAD | `collectCrumblingInstances(i→setVisible(false))` 隐藏**全部**实例、回来 `setVisible(true)+setChanged` | 是(planFrame 两引擎都驱动) |
| 我方自绘 LOD | `DistantTrackRenderer`/`DistantTrainRenderer` 让位门 | `distSq < (视距×16)²` 让位真渲染,否则我画 | — |

**统一判据**:`entity.position()`(或 unit/BE 锚点)到相机 **3D 球形距离 > `getEffectiveRenderDistance()×16`**。全挂点同锚点同阈值 → 各路径同步显隐,无"车没了附件还在"。

---

## 3. 踩坑清单(每条都返过工)

1. **继承 `@Shadow` 崩启动**:`@Shadow` 父类字段(embedding 在 ContraptionVisual、entity 在 AbstractEntityVisual)→ `InvalidMixinException @Shadow field not located` → apply FATAL → 崩。**改 `@Accessor` 挂字段真正声明的类**(`AccessorContraptionVisual`/`AccessorAbstractEntityVisual`),子类继承接口 cast 取用。
2. **crash-report 主栈误导**:mixin apply FATAL 会级联成别的次生异常(如 iris config NPE),crash-report 只抓次生。**排查崩溃必查 `latest.log` 的 `FATAL`/`Mixin apply ... failed`**,不能只看 crash-report 主栈。
3. **HEAD+cancel 跳过引擎 setup**:`CarriageContraptionVisual.beginFrame` HEAD+cancel 隐藏 embedding **无效**——cancel 跳过了引擎给 embedding 分配 matrixIndex/注册 track 的每帧 setup,embedding 掉出 flush 集或 GPU 索引失效,照旧用上帧矩阵。**改 TAIL 不 cancel**:让 beginFrame 跑完,只在末尾覆盖 pose。
4. **竖直方向:圆柱加载 vs 球形渲染**:区块**水平圆柱加载**(水平视距×全世界高度)但**球形距离渲染**。相机正下方 section **`isSectionCompiled` 恒 true**(已加载),哪怕竖直早超视距 vanilla 不画它。→ 让位/剔除**绝不能用 isSectionCompiled 判竖直**,用 **3D 球形距离**。
5. **`getViewDistance` 对 global BE / Flywheel 无效**:track BE `shouldRenderOffScreen=true`(global BE)且 Flywheel on 走 visual,`getViewDistance` clamp 完全落空。**找实际 draw entry**(Flywheel visual 的 beginFrame / vanilla renderSafe),别挂 getViewDistance。
6. **Flywheel 双渲染器互斥**:改 track 剔除必须两条都挂(TrackVisual for backend-on、TrackRenderer for backend-off),否则切 backend 就漏。
7. **actors 不在 embedding**:`ContraptionVisual` 的注释宣称"actors 全走 embedding"是**错的**——没 `createVisual` 的 MovementBehaviour(controls/blaze_burner)走 vanilla `renderActors`,不经 embedding。别信注释,反编译验证。
8. **同锚点原则**:各路径剔除阈值/锚点必须一致(都 `entity.position()` + `视距×16`),否则边界带出现"车体消失但 actor/bogey 还在"的撕裂。
9. **kinetic 剔除的三个坑**:① `*ActorVisual`(saw/deployer/drill)`extends ActorVisual` 是 **contraption 内 actor 非放置块**、无 `pos` 字段——误入多目标 mixin 会 accessor cast 崩,须排除(它们归 contraption 路径);② `RotatingInstance` **不继承 `TransformedInstance`**(车体那套 `setZeroTransform` 用不了),隐藏用 `Instance.setVisible(false)`(和皮带隐藏物品同机制,ClrwlEngine 认);③ 隐藏挂点用 `collectCrumblingInstances(Consumer<Instance>)`(BlockEntityVisual API,字节码核实枚举=`_delete` 的全部 drawable 实例、零分配),一处多态遍历隐藏全实例,**无需逐子类 @Shadow 各自实例字段**。
10. **kinetic 双分区 + 别只扫一个包**:未 override beginFrame 的(shaft/cog/gearbox/belt/fan/waterwheel/saw…)→ 挂**基类** `KineticBlockEntityVisual` 加 `SimpleDynamicVisual`.beginFrame 一处继承覆盖;**16 个 override 了 beginFrame** 的机器 → 基类 beginFrame 被 shadow,须多目标 `@Inject` HEAD 各自 beginFrame。**坑**:这 16 个里 **6 个不在 `content/kinetics/` 包**(`contraptions.bearing.BearingVisual`/`contraptions.pulley.AbstractPulleyVisual`/`contraptions.gantry.GantryCarriageVisual`/`contraptions.elevator.ElevatorPulleyVisual`/`fluids.pipes.valve.FluidValveVisual`/`logistics.depot.EjectorVisual`)——只扫 kinetics 包会漏,它们照样 `extends ShaftVisual/KineticBlockEntityVisual` 是放置态旋转机器。分区靠 `javap` 全 `content/**/*Visual` 逐类查 beginFrame + 父类确认,别凭记忆(`SteamEngineVisual` 还直接 extends `AbstractBlockEntityVisual`;`StabilizedBearingVisual`/各 `*ActorVisual` 是 ActorVisual 须排除)。

---

## 4. 当前联动清单(29 文件,~2700 行)

- **自绘 LOD**:`DistantTrackRenderer`(直线 bakeSection + 弯道 bakeTurn,让位门 3D 距离)、`DistantTrainRenderer`(车厢网格)、`DistantBogeyMeshes`(转向架快照)、`DistantMesh`/`DistantMeshBuilder`(24B 顶点格式 VBO/VAO)、`DistantShaders`、`DistantLightSampler`、`CarriageMeshBaker`。
- **火车/轨道剔除挂点**:`MixinTrackVisual`(Flywheel 弯道)、`MixinCarriageContraptionVisual`+两 Accessor(火车车体+bogey)、`MixinContraptionEntityRenderer`(contraption vanilla 三 pass)、`MixinStationRenderer`(灰箭头)、`MixinTrackRenderer`(backend-off 回退)。
- **动态结构补画**:`DistantContraptionManager`(客户端快照 Map,加载内实时刷/卸载冻结)+`DistantContraptionRenderer`(LOD 画冻结/实时快照)+`MixinContraptionVisual`(通用 contraption 车体 embedding 剔除)。
- **放置态 kinetic 剔除**:`KineticCull`(共享判据 + `collectCrumblingInstances` 隐藏)、`MixinKineticBlockEntityVisual`(shaft/cog/belt/fan 家族基类 beginFrame,未 override 者)、`MixinKineticMachineVisuals`(16 个 override beginFrame 的机器多目标,含 contraptions/fluids/logistics 包的 bearing/pulley/gantry/elevator/fluidvalve/ejector)、`MixinKineticBlockEntityRenderer`(backend-off BER)、`AccessorAbstractBlockEntityVisual`(pos)。
- **数据**:`DistantTrainManager`(客户端位姿插值)、`CreateTrainSampler`(服务端采样)、`DistantTrainProtocol`(payload)、`DistantOcclusionDebug`(诊断)。
- **门控**:`ClientVoxyMixinPlugin.getMixins` 里 `createInstalled` 统一 gate;`Voxy.java` 运行时注册 renderer/事件。

## 5. 性能与兼容审计结论

### 5.1 性能(已审计并修复主要项)

每帧入口 = `runPipeline → LodPipelineHooks.beforeTranslucent`(一次/帧),内两个 renderer 各遍历一次。

**已修**:
- **[大网络最大项] DistantTrackRenderer 死 `isSectionCompiled`**:让位门早已改纯距离(`distSq<beViewDistSq`),但每帧每单元仍调 `isSectionCompiled`(sodium section 表查),结果只 debug 用。→ 整块移入 `if(DistantOcclusionDebug.isActive())`。省下 O(视野内单元数) 次/帧 section 查。
- **[GC] DistantTrainRenderer.interpolate 每车厢每帧 new CarriagePose**(且在距离剔除**前**,远车厢也分配)→ 内联为 locals(px/py/pz/yaw/pitch),剔除前只做几个 lerp,零分配。删 `interpolate()` 死方法。
- **[浪费] handover 段的 `isSectionCompiled`+`BlockPos.containing` 即使超 band 也算** → gate 进 `if(distSq<handoverSq)`,复用 `MutableBlockPos`。
- **[native 泄漏] bezier/bogey bake 异常路径不 free**:`DistantMeshBuilder.build()` 是唯一 memFree 点,异常 return 泄漏 ≥64KiB(坏 partial model 每次重烘都泄)。→ 加 `discard()`,bezier/bogey catch 里调用。
- **[小] renderCommon 收 `new Matrix4f(viewport.MVP)` 拷贝** 从不 mutate → 直接传 `viewport.MVP`。

**已经做对(勿动)**:mixin beginFrame 路径**无分配无锁**(Flywheel 并行 planFrame 安全,ZERO 矩阵只读 static);光照采样 1s 节流;重烘 checksum 5s 节流且在 ClientTickEvent(非渲染 hook);车厢/bogey 网格烘一次缓存;服务端采样 5tick 节流+快照避锁+可选 worker;draw 循环复用单 Matrix4f;DistantShaders 用 thread-local MemoryStack;共享索引缓冲。

**待办(非每帧,后续)**:全网络同步重烘(任何轨道编辑触发,主线程 hitch)→ 改 per-graph checksum 差异重烘 + CPU 网格组装移 worker(仅 GL 上传回主线程)。

### 5.2 兼容(已审计,行为矩阵)

三场景:**S1** 纯 Create(默认 EngineImpl on,NormalRenderPipeline,无 iris/colorwheel/nowheel);**S2** Create+iris+colorwheel(ClrwlEngine,已验证);**S3** backend off(`/flywheel backend off` 或无实例化 GPU)。

**关键结论:Flywheel `DynamicVisual` 派发引擎无关**(`impl/visualization/storage/Storage.setup` 按 `instanceof DynamicVisual` 每帧调 beginFrame,两引擎共用 impl 层)→ `MixinTrackVisual`/`MixinCarriageContraptionVisual`/`MixinContraptionVisual` 在 EngineImpl 与 ClrwlEngine **行为一致**。**不依赖 colorwheel/nowheel**(nowheel 仅出现在注释)。**纯 Create(S1)基本全 correct。**

- **missing-class 安全**:7+ create mixin + 2 accessor 全 `createInstalled` gate;renderer/采样 `ModList.isLoaded("create")` gate;Create 6.0.10 硬 jarjar flywheel(不存在"无 Flywheel"场景,只有 backend off=S3)。
- **config off-switch trap-free**:`isRenderingEnabled()=false` → 每 mixin 早返真渲染,LOD renderer 早返,烘焙 gated;`MixinTrackVisual` 关 LOD 当帧重建(rendering=false⇒beyond=false⇒重建),`MixinCarriageContraptionVisual` TAIL-after 真 pose 已设 → 无"删了不画"陷阱。
- **GAP 1(HIGH,已修)**:S3 backend off,火车 bogey 走 `CarriageContraptionEntityRenderer.render` 的 super 之后代码(offset 28-99),`MixinContraptionEntityRenderer` HEAD-cancel 的是 super(父类)拦不住子类 → 轮子浮空无车体+与我方 LOD bogey 双画。触发=视距<15 区块 && backend off。**修:`MixinCarriageContraptionEntityRenderer` twin HEAD-cancel 子类 render**(整个含 bogey)。`OrientedContraptionEntityRenderer` 只 override shouldRender,无此问题,基类 cancel 已覆盖。
- **GAP 2(LOW-MED,设计决策已定)**:剔除 mixin 只 gate `isRenderingEnabled()`,不 gate `distantTracks`/`distantTrains`。**决策:anti-float 剔除常开(跟随 isRenderingEnabled),`distantTracks/Trains` 只控 LOD 补画**。理由:关补画时若也关剔除,vanilla contraption 会在 voxy LOD 区浮空(voxy 保活区块引入的 bug)——消失优于浮空。代价:关 distantTrains 时超视距火车在边界消失而非 Create 原生浮空(可接受,因浮空本就是 bug)。
- **GAP 3(LOW,intended)**:`DistantTrainRenderer` handover `min(224,(rd-2)*16)` vs vanilla cull `rd*16` → `[(rd-2)*16, rd*16]` 双画重叠带(防缝隙,微 shimmer,非缺陷)。
- **版本耦合**:`required:true` 绑 Create-6.0.10/flywheel-1.0.6 符号名(`VisualEmbedding.transforms`/`BogeyVisual.hide`/`TrackVisual.collectConnections`/`getViewDistance`/`renderSafe`);Create/Flywheel 更新改名→ apply 崩。升级必重核。
- **线程安全观察(低)**:`MixinTrackVisual` 在 beginFrame(并行 frame-plan)删/建实例(stock 在构造建),novel;colorwheel 已验证,EngineImpl 下默认引擎压测留意。

## 6. 动态结构(通用 contraption)联动 — 已开工

火车经验向通用 movement contraption(活塞平台/轴承/升降机/mod 的飞行器·船)推广。`AbstractContraptionEntity` 仅 4 子类:`CarriageContraptionEntity`(火车)、`GantryContraptionEntity`、`ControlledContraptionEntity`(轴承/门/活塞)、`OrientedContraptionEntity`(飞行器/船基座,多 mod 继承)。

### 6.1 已落地(超视距剔除,不浮空)

- **vanilla 三 pass(structure SBB/renderBlockEntities/renderActors)**:`MixinContraptionEntityRenderer` 挂**父类 `ContraptionEntityRenderer.render`**,一处覆盖**所有** contraption 类型(火车/活塞/轴承/飞行器通吃)。`OrientedContraptionEntityRenderer` 只 override shouldRender 无 after-super 画,基类 cancel 全覆盖。
- **Flywheel embedding 车体**:通用 contraption 车体走**基类 `ContraptionVisual`**(火车 `CarriageContraptionVisual` 是唯一子类)。**`MixinContraptionVisual` 挂基类 `beginFrame` TAIL,超视距 zero embedding pose**,覆盖所有非火车 contraption 车体;`instanceof CarriageContraptionVisual` 排除火车(火车保留已验证的 `MixinCarriageContraptionVisual`=embedding zero + bogey hide,其 beginFrame 调 super 会触发基类 mixin 故需排除防双处理)。embedding 是 ContraptionVisual own field(直接 @Shadow),entity 走 `AccessorAbstractEntityVisual`。
- **同锚点同阈值**:全部 `entity.position()` 3D 距离 > `视距×16`,与火车/轨道/站点一致。

### 6.1b 远景补画:客户端静态快照(已落地)

用户拍板方案(2026-07-14):**不做流式运动补画,做客户端快照留影**。通用 contraption 是实体、玩家必然先经过才离开,数据本就在客户端 → 纯客户端、零服务端/协议/发包。
- **原理**:contraption 在视距内时客户端持续刷新其"方块网格 + 完整变换";越过视距边界即**冻结**,远景静态显示(停在离开时的位姿/旋转方向,风车定格)。
- **变换直取**:`AbstractContraptionEntity.applyLocalTransforms(PoseStack,float)` 是 public abstract、客户端专属、各子类多态(Oriented 偏航俯仰/Controlled 绕轴/Gantry 恒等)——客户端 `new PoseStack → applyLocalTransforms → M_local`,`× translate(entity.position()-cam)` 即完整变换,**无需逐子类反推**(agent 报告确认 `M_world=T(pos)·M_local`,rigid,mesh 从 `getBlocks()` raw local pos 烘,无偏移)。
- **组件**:`DistantContraptionManager`(快照 Map<UUID>,`updateNear` 每 tick 刷视距内、冻结视距外、60s 无刷回收;bake 复用 `CarriageMeshBaker`+`getBlocks→ShapeBlock`≤127)+`DistantContraptionRenderer`(LodPipelineHooks,画 `[evd*16, LOD视距]` 冻结快照,`VP·T(pos-cam)·local`,复用 DistantShaders/stencil=3/光照 uniform)。bake 在 ClientTickEvent 非 render(避 GL clobber)。config `distantContraptions`(默认开)。
- **让位**:视距内 vanilla/Flywheel 画(现有剔除 mixin 管超视距);`[evd*16, LOD视距]` 快照静态接管(vanilla 已被剔,无双画);超 LOD 视距不画。**语义自洽**:没经过的 contraption 无快照不显示。
- **排除列车(2026-07-14 修)**:`update` 循环遍历 `AbstractContraptionEntity` 时必须 `if (ce instanceof CarriageContraptionEntity) continue;`——**列车(`CarriageContraptionEntity extends OrientedContraptionEntity`)有专属远景系统**(`DistantTrainRenderer`+服务端 `CreateTrainSampler` 跨卸载区块推位姿),漏排会双画且列车驶离后在原地留冻结虚影。非列车的 mounted contraption(矿车结构=OrientedContraptionEntity 非 Carriage)不排除,照旧快照。
- 部署哈希 474D396C22FD(列车排除修复见 §6.3 当前哈希)。**待验证**:走过风车/轴承门/活塞平台/矿车 contraption 后离开,远处应留静态定格(旋转方向保持);走回刷新;超 LOD 视距消失;组装变化后快照是否过时(mesh bake once,罕见结构变可能陈旧);**列车驶过不再留虚影**。

### 6.1c 加载但超视距 = 实时 LOD(用户洞察,升级快照方案)

- **用户洞察**:区块**水平圆柱加载**(水平视距×全高度)vs **球形距离渲染**——竖直大矿井底下的机器,区块加载、实体/运动数据**实时活着**,只是竖直超球形视距 vanilla 不画。原快照方案一刀切"视距外冻结"把这种也冻结了,应实时 LOD 渲染(看底下机器运作)。
- **修**:`update`(原 updateNear)去掉"只更新视距内"过滤,改**更新 LOD 半径内所有加载 contraption**(不管距离)。→ 加载的持续实时刷新(动态);区块卸载(水平走远)实体自动从 `entitiesForRendering` 消失→快照停刷冻结(静态)。两种情况自动区分:**加载但超视距=实时动态,卸载=静态快照**。maxDist(LOD 半径)过滤仅剔超 LOD 的(不画)。性能:`entitiesForRendering` 本就是 client 加载实体(有限),filter contraption(少),开销小。
- 部署哈希 556FC6BC6B16。**待验证**:竖直矿井底下(视距不足)机器 contraption 应**实时运作**渲染在 LOD 中(非冻结);水平走远的仍静态快照。

### 6.1d 放置态 kinetic BE(传动杆/齿轮/机器动件)超视距剔除(已落地)

- **用户诉求**:Create 机器方块 = 静态模型部分 + 运动部分;关掉 voxy 见运动部分(旋转 shaft/齿轮)在超视距位置浮在体素 LOD 上。要"水平超视距静止、竖直超视距接入 LOD、正确剔除不浮空"。
- **判读**:静态部分在 LOD 正常 = voxy **核心体素 LOD**(所有方块体素化)。运动部分穿透 = 放置态 `KineticBlockEntity` 的旋转件走 **Flywheel BE visual**(`RotatingInstance`,GPU 按转速自转),**无距离剔除**,同 track/contraption 的 Flywheel 实例化路径通病。
- **方案取舍(用户 2026-07-14 拍板"剔除·不浮空")**:agent 字节码核实两档——**A 剔除**(超视距隐藏运动件,中等工程,共同基类可一处挂)vs **B 竖直接入 LOD 看转动**(voxy 侧无实例化重写 Flywheel kinetic 渲染,成千上万独立旋转子模型,且**水平超视距的机器区块已卸载 client 无 BE 数据 `getSpeed` 读不到**永远画不出转动)。B 工程巨大收益边际,**选 A**。角度公式本身简单可复现(`speed·renderTime·0.3+相位 mod360`)是 B 里唯一便宜处,存查。
- **落地(4 类 + 1 accessor)**:见 §3 坑 9/10。`MixinKineticBlockEntityVisual`(基类 `KineticBlockEntityVisual` 加 `SimpleDynamicVisual`.beginFrame,覆盖未 override 的 shaft/cog/belt/fan/gearbox/waterwheel/saw 家族)+ `MixinKineticMachineVisuals`(**16** 个 override beginFrame 的机器多目标 HEAD cancel,含 bearing/pulley/gantry/elevator/fluidvalve/ejector 这 6 个 content/kinetics 包外的)+ `MixinKineticBlockEntityRenderer`(backend-off BER renderSafe cancel)+ `AccessorAbstractBlockEntityVisual`(取 `pos`)+ `KineticCull`(共享:`视距×16` 3D 球距判据 + `collectCrumblingInstances→setVisible` 隐藏/恢复)。
- **水平/竖直自洽**:水平超视距→区块卸载→BE 消失→visual 消失→只剩静态体素(自动"静止");竖直超视距→区块仍加载(圆柱)→visual 活着但被距离剔除隐藏→只剩静态体素。两向都"正确剔除不浮空"。**未做**竖直看转动(B,已排除)。
- **性能**:稳态每 visual 每帧一次平方距离比较(状态翻转门控),`collectCrumblingInstances` 零分配、`setVisible` 幂等;`beyond` 集才走隐藏遍历(通常竖直一列,少)。与 nowheel 正交(它做遮挡,这做距离,互不干涉)。
- 部署哈希 `BE01DDC90FF9`。**待验证**:①矿井往下/大机器阵拉远,旋转 shaft/齿轮/机器动件超视距**消失**不再浮空;②走回视距内恢复旋转;③纯 Create(S1 默认引擎)与 colorwheel(S2)行为一致;④backend off 也不浮空。

### 6.1e 非 kinetic 放置态 BE visual 也浮空(同根 bug,待用户定)

- **同根**:任何 Flywheel BE visual(不止 kinetic)超视距都浮在 LOD 上。全包扫描发现另有一批**放置态 override-beginFrame visual 直接 extends `AbstractBlockEntityVisual`**(非旋转):`BlazeBurnerVisual`(烈焰人脸/火)、`FunnelVisual`(漏斗翻板)、`BeltTunnelVisual`(隧道翻板)、`ToolBoxVisual`(工具箱盖)、`AnalogLeverVisual`、`StickerVisual`、`GlassPipeVisual`、`SchematicannonVisual`、`FrogportVisual`/`PackagerVisual`/`PSIVisual`、`BogeyBlockEntityVisual`;bf=0 的 `BrassDiodeVisual`/`SignalVisual`/`TrackObserverVisual`。都会同样浮空。
- **未做**:用户诉求限"机器方块(旋转)",这批不是。要全覆盖须把 `MixinKineticBlockEntityVisual` 的基类从 `KineticBlockEntityVisual` 上移到 flywheel `AbstractBlockEntityVisual`(覆盖所有非 override BE visual)+ 把这批 override-者并入 `MixinKineticMachineVisuals`。代价:会触及**所有** mod 的 BE visual(agent 判"overreach";但对它们剔除也是对的,因它们同样浮空)。机制/风险与现方案相同(都 `pos`+`collectCrumblingInstances`)。**待用户拍板是否扩到全放置态 BE**。

### 6.2 待测/待办

- **通用 contraption 的主-context 子 visual**:火车 bogey 是主 context(非 embedding)故单独 hide;通用 contraption 是否有类似的主-context 活动部件 visual(轴承旋转件/gantry 滑块等)残留浮空,**须实测**(bearing/gantry/elevator 各拉远看)。若残留,定位其 visual 同法 hide/剔。`BearingVisual`/`GantryCarriageVisual`/`ElevatorPulleyVisual` 疑似放置态方块 visual(非 contraption 车体),需辨明。
- **远景 LOD 补画**:火车有 `DistantTrainRenderer` 自绘网格;通用 contraption 形状动态(任意组装方块 + 运动),LOD 网格化成本高、收益低(运动结构远景占比小),**暂只做剔除不做补画**,视需求再评估。
- **协议/服务端采样**:火车经 `CreateTrainSampler` 推送位姿做远景补画;通用 contraption 若将来要补画,需类似采样(但见上,暂不做)。

### 6.3 部署状态

哈希 `1ECE212A96D5`(双包:移山计划 + 测试渲染)含:性能五修 + GAP 1(backend-off bogey)+ 动态结构 embedding 剔除 + 动态结构实时/冻结快照(6.1b/6.1c,含列车排除修复)+ 放置态 kinetic 16 机器超视距剔除(6.1d)+ **联动渲染距离配置(客户端 UI)+ 采样带宽对齐 + 专用服统一上限配置 `voxy-server.toml`(§7)**。

## 7. 配置项(客户端 UI + 带宽)

**距离基线(实测)**:`NormalRenderPipeline` 里 voxy LOD 半径 = `32 × sectionRenderDistance` **block**(默认 16→512 block=32 区块;UI 上限 64→2048 block=128 区块;TOML 注释"512 chunks"是**错的**,实为 block)。三个 Create 联动渲染器全部以此为上限。

**两套 UI 都要同步**:voxy 有**两个**配置界面——① Sodium 视频设置里的"联动"页(`VoxyConfigMenu`,Sodium 0.8 原生 API,用户主要用这个)② NeoForge Mods 菜单 TOML(`VoxyNeoForgeConfig`)。加字段时**两边都得补**(2026-07-14 曾漏补 Sodium 页,截图暴露)。`VoxyConfig` JSON 是权威值,两 UI 都读写它。

**新增 config**(`VoxyConfig` JSON + `VoxyConfigMenu` Sodium 页 + `VoxyNeoForgeConfig` TOML,三处同步):
- 开关:`distantTrains`/`distantTracks`/`distantContraptions`(原有,之前没进 UI,现补上)+ `distantKinetics`(新,kinetic 剔除总开关,关=Create 原生可能浮空)。
- 距离上限(区块,0=跟随 LOD 半径):`distantTrainMaxChunks`/`distantTrackMaxChunks`/`distantContraptionMaxChunks`。经 `VoxyConfig.createRenderDistance(maxChunks)=min(maxChunks×16, 32×sectionRenderDistance)` 换算,各渲染器统一取用;contraption 的 manager 快照与 renderer 绘制用同一上限(不快照不画的)。

**带宽对齐(核心)**:`CreateTrainSampler` 原先无脑发到 3072 block,而客户端只画到 LOD 半径(≤2048,默认仅 512)——**512–3072 那圈一直在发但客户端从不画**(面积 ∝ r²,默认浪费 ≈97%)。控制点 `commonImpl/compat/create/DistantTrainConfig`(dist-safe,无客户端类引用,同 `SableContraptionRenderDistance` 套路)合成**两路输入取 min**:
- **客户端偏好**(`VoxyConfig.save→syncDistantTrainConfig→updateClientConfig`):本机想要多远。**集成服(单人/开房主机)** 同 JVM→采样窗口收到=客户端实际渲染距离,直接砍浪费;`distantTrains` 关则停发(0 带宽)。专用服无客户端写此→留默认 `HARD_MAX`(不额外约束)。
- **服务端上限**(`CreateServerConfig→voxy-server.toml`,`ModConfig.Type.SERVER`,存 `world/serverconfig/`,Create 门内注册):管理员**统一上限**,对所有玩家生效。`distantTrainsEnabled`(总开关)+ `maxTrainStreamChunks`(默认 128=2048 block,范围 8–192;192=旧 3072 行为)+ `sampleIntervalTicks`(默认 5,范围 1–40;客户端 `handlePoses` 实测包间隔自适应插值,故可安全调)。config 加载/重载 push 进控制点。
- 采样器读 `enabled()=client&&server`、`maxDistance()=min(client,server)`、`sampleInterval()=server`。**专用服**:客户端桥留默认→有效值=服务端配置(管理员单点掌控);**集成服**:两者取小。硬顶 3072 恒在。

轨道/结构/kinetic 是纯客户端零网络。采样间隔翻倍=带宽减半(远景稍卡),现由服务端配置开放。

**带宽量级**(每列车每采样 ≈ `42 + 109×车厢数` byte,4 次/秒 → 约 **0.44 KB/s/在窗车厢**):1 列 3 厢≈1.5KB/s;10 列×4 厢≈19KB/s;100 列×5 厢≈235KB/s。shape 每车厢只发一次(缓存)。采样间隔 5 tick 硬编码(未开放,减半间隔=减半带宽是进一步旋钮)。**待用户实测**:①各类 contraption 超视距整体消失不浮空;②主-context 子部件有无残留(6.2);③放置态 kinetic(轴/齿轮/press/mixer/bearing/gantry/…)超视距不浮空、走回恢复旋转;④非 kinetic 放置态 BE(6.1e)是否要一并剔。
