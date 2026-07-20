# Create 远景快照:驻留管理与持久化

**起因**:0.3.0 → 0.3.1 后测试者报告帧率大幅下降。嫌疑集中在 `createLodRadius` 的 ×16 修复上——它把 Create 远景渲染的半径从 512 格放到 8192 格(面积 ×256),而这些渲染器**每帧遍历全表、只按距离筛、没有视锥剔除**,快照本身又**没有内存上限**。

**结论先行**:视锥剔除只减 draw call,不减显存;而驻留集无界时,省下的那一半开销会被持续增长吃掉。真正要解决的是**驻留管理**,持久化是它的必要组成(淘汰要能不丢数据),跨会话保留只是副产品。

---

## 0. 现状事实(已核实,含 file:line)

### 0.1 两条路径的数据形状

| | `DistantContraptionManager` | `KineticSnapshots` |
|---|---|---|
| 键 | `UUID` → `Snapshot` | `long` section 位置 → `Bucket` |
| 源数据 | **未保留**(见下) | `Map<BlockPos, Snap> geoms` |
| 网格 | `BakedCarriage`(仅 GPU 句柄) | `DistantMesh`,可由 `geoms` 完整重烘 |
| 内存上限 | **无** | 距离淘汰 `createRenderDistance(0)+32` |
| 维度隔离 | **淘汰时不比较 `dim`** → 跨维度永久累积 | section 键含维度语义 |

### 0.2 阻塞项(按影响排序)

1. **contraption 源数据丢失**:`bakeContraption`(DistantContraptionManager.java:254-278)构造的 `List<ShapeBlock>` 在 :277 出作用域即丢弃,`Snapshot` 无字段承接。实体消失后 CPU 侧**没有任何东西可持久化**。
2. **烘焙无「组装/上传」分离**:`DistantMeshBuilder.build()`(:166-180)在同一次调用里做 GL45 DSA 建对象并 `memFree` 暂存缓冲;`CarriageMeshBaker.bake` 在 :157 无条件调用它。**不拆开就无法把烘焙移出渲染线程。**
3. **kinetic 捕获天然绑渲染线程**,且不止因为 GL:`capture` 会重放机器自身的 BER(:474-475),挂在一个 Flywheel worker 不得观察到的线程作用域门后(:57, :470-485)。
4. **`Snap.generic` 是录制的顶点流,不可重导出**——注释(:46-48)记录了替代方案失败的原因(catnip `SuperByteBuffer` 在渲染 pass 外为空)。这是 kinetic 源数据里最重的一块。
5. **voxy blockId 不可用于外部持久化**(理由见 §2.1)。
6. **无 CPU 侧可见性查询**:全仓无 `testAab/isVisible`;视锥平面直接进 GPU uniform(HierarchicalOcclusionTraverser:188-193),HiZ 结果只作为 256 项淘汰表回读。
7. **无可复用的 LRU**:`ActiveSectionTracker.lruSecondaryCache`(:43)是私有内联、绑 `WorldSection` 回收的,无 get/put/evict 接口。
8. **`UploadStream.INSTANCE` 是共享 64MB 环、仅渲染线程**(:175-176),耗尽时强制 flush 并最多 10 次 `glFinish()` 重试。新子系统挤占它有风险。
9. **无统一每帧调度器**:节流分散在三处互不相关的地方(AsyncNodeManager:256、:488-491、HierarchicalOcclusionTraverser:220-226)。

### 0.3 顺带发现的既有缺陷

- `Snapshot.baked`(:45)写于 :137,**全仓无人读**——死字段。
- `Snapshot.lightPacked` **被复用为哨兵**:`>= 0` 在 :152 门控整个受控 contraption 冻结分支。重算或给默认值会静默改变状态机。
- `CarriageMeshBaker.bake` 失败路径**无 discard**:异常逃出 :157 的 `builder.build()` 会泄漏 `memAlloc` 的原生缓冲。`KineticSnapshots.rebake`(:744-746)有守卫,两条路径不一致。
- `DistantMesh` 头注释(:25)写 24 字节/顶点,实际 `STRIDE = 28`(:33)——按注释估算显存会少 14%。同一注释把偏移 23 称作 padding,实际 DistantMeshBuilder:40 在那写 face index。
- ±127 局部坐标上限烘在协议里(DistantTrainProtocol.java:28),超出的方块在 :263-265 被静默丢弃——**任何持久化方案都救不回**,因为丢在 bake 之前。

---

## 1. 分期:先止血,再治本

事实表明完整方案的前置改造不小(源数据保留 + 烘焙拆分 + 自建视锥 + 自建 LRU)。因此分成三期,**P0 单独就能解决大部分帧率问题,且不依赖持久化**。

### P0 — 止血(不碰持久化,风险最低)

目标:让驻留集有界、让每帧工作量与「可见的东西」而非「走过的地方」成正比。

1. **contraption 补距离淘汰 + 维度隔离**。现在完全没有。淘汰判据复用 kinetic 的形状:超出 `createRenderDistance(distantContraptionMaxChunks) + 32` 即释放;`dim` 不匹配当前维度的直接释放(现在这些永久留着且从不绘制)。
   - ⚠️ 保留现有的存在性清理逻辑(:225-250)。那段注释解释了为何不能用时间过期(实体在几十格外就掉线,TTL 会误删合法快照),这个理由仍然成立。距离淘汰是**额外**的上界,不是替换。
2. **`*MaxChunks` 给有限默认值**。8192 格外的 Create 机器在屏幕上只有几个像素。建议默认 `distantContraptionMaxChunks = 64`、`distantTrainMaxChunks = 96`、`distantTrackMaxChunks = 96`、kinetic 走 `distantKineticMaxChunks = 48`(新增)。**这三个上限在 ×16 修复前从未生效过**,所以这是它们第一次真正起作用。
3. **CPU 视锥剔除**。需自建(§3.1)。只减 draw call,但在 P0 里它便宜且立即见效。
4. **修 §0.3 的既有缺陷**:删死字段、补 bake 失败路径的 discard、订正 `DistantMesh` 注释。

**验收**:测试者在同一场景下 `sectionsDrawnLastFrame` 显著下降、帧率恢复;`/voxy debug kinetics` 的驻留数不再随游玩时长单调增长。

### P1 — 源数据保留(持久化的前置,本身不落盘)

1. `Snapshot` 增加 `List<ShapeBlock> source` 字段,`bakeContraption` 保留而非丢弃。堆开销:500 方块 ≈ 500 × (3B 坐标 + 引用) ≈ 6 KB,对比其 GPU 网格是小数。
2. 拆 `DistantMeshBuilder.build()` 为 `assemble() → CpuMesh`(纯 CPU,可 worker)+ `upload(CpuMesh) → DistantMesh`(仅渲染线程)。这是 P2 按需重烘的前提。
   - kinetic 的 `rebake` 已经是「从 `geoms` 重建」的干净形状,只需接上同一个拆分。
   - **contraption 的 `capture` 仍须在渲染线程**(需要活实体),但**重烘**可以离线程——这正是 P2 需要的那一半。

### P2 — 驻留管理 + 持久化(治本)

三层:

| 层 | 内容 | 位置 | 可否重建 |
|---|---|---|---|
| 索引 | 键 + 世界坐标 + 维度 + 源数据大小 | 内存,常驻。每条约 48B,一万条 ≈ 0.5MB | 开世界时从存储读 |
| 源 | `ShapeBlock` 列表 + 冻结姿态 / `Snap` 列表 | **aux 表**,按需读入 | 不可——实体/BE 消失后世界里没有它 |
| 网格 | VAO/VBO | GPU,**只给通过视锥+距离的** | 可,从源重烘 |

**核心不变量:淘汰 ≠ 删除。** 释放网格、源退回存储。这让淘汰变成廉价可逆操作,判据可以比现在激进得多。

---

## 2. 数据格式

### 2.1 为何不用 voxy blockId

调查明确否决,四条独立理由:

- id 绑定单个 `Mapper`(WorldEngine.java:65),而 LOD 库是**显式可删的再生缓存**(RocksDBStorageBackend 注释 :116-121)。删库后 id 全部重排,外部持有的列表**静默解码成别的方块**——id 空间稠密,不会落到空洞上,所以无从检测。
- 无「只查不建」的正向 API:`getIdForBlockState`(Mapper.java:260)未命中即铸造并经 `persistStateEntry`(:214)**同步写 RocksDB**。编码一个 contraption 会污染持久 id 表。
- 反向不线程安全:`blockId2stateEntry` 是裸 `ObjectArrayList`(:55),读无锁、**无边界检查**,陈旧 id 抛 `IndexOutOfBoundsException` 而非退化为空气。文件里两处 TODO 自承。
- **既有先例反对**:`DistantTrainProtocol`(:18-20)明确用原版 blockstate 注册表 id,理由是「registry sync keeps them consistent」。

### 2.2 采用:调色板 + `BlockState.CODEC`

```
contraption 条目 (aux 表 "create_contraptions", 键 = UUID 高低位拼 long):
  u8  format
  u8  paletteCount
  [paletteCount] × BlockState NBT (CODEC 编码)
  u16 blockCount
  [blockCount] × { i8 x, i8 y, i8 z, u8 paletteIdx }
  f32[16] 冻结姿态矩阵
  f64 x, y, z
  utf  维度 id
```

一个 500 方块、25 种状态的 contraption ≈ 25 × ~60B + 500 × 4B + 88B ≈ **3.6 KB**。相比其 GPU 网格(见 §2.3)是零头。

选 `BlockState.CODEC` 而非注册表 id 的理由:注册表 id 依赖 mod 集合与加载顺序,跨会话不稳定;NBT 形式带方块名与属性,且**能走原版 DFU 处理重命名**——这才是我原本以为 voxy blockId 能给的东西。

**位置索引**(第二张 aux 表 `create_contraption_index`,键 = section 位置):`[UUID, ...]`。contraption 需要它才能做「由近及远逐步烘焙」;kinetic 的键本身就是位置,不需要。这是共用抽象里必须留的口子。

### 2.3 显存估算(订正后)

`STRIDE = 28` 字节/顶点(**不是注释写的 24**),4 顶点/面。一个 500 方块 contraption 若平均 3 个可见面/方块:
`500 × 3 × 4 × 28 ≈ 168 KB`。

1000 个驻留快照 ≈ **164 MB 显存**,还不含 VAO/VBO 对象开销。这就是无界驻留在 8192 格半径下的实际代价量级。

---

## 3. 共用抽象

```java
// 驻留管理器,kinetic 与 contraption 共用
class SnapshotResidency<K> {
    void  note(K key, double x, double y, double z, ResourceKey<Level> dim);  // 索引登记
    void  tickResidency(Viewport<?> viewport);   // 决定晋升/降级,受预算约束
    void  forEachResident(BiConsumer<K, DistantMesh> draw);
}

// 各自实现
interface SnapshotType<K> {
    CpuMesh assemble(Source src);        // 纯 CPU,可 worker
    byte[]  serialize(Source src);
    Source  deserialize(byte[] data);
    String  auxTable();
}
```

**共享**:索引、LRU、字节预算、晋升/降级、上传队列与每帧配额、由近及远的补烘顺序。
**各自**:`assemble` / `serialize` / 位置来源。

### 3.1 视锥测试(须自建)

`Viewport.frustum` 在相机相对空间——`Viewport.update()`(:103-111)把相机拆成整数 section(`floor(cam)>>5`)与 `innerTranslation`。**世界坐标不能直接测**,必须先平移进该空间。

写一个 `boolean testAabb(Viewport<?> vp, double minX..maxZ)`,六平面测试。contraption 的包围盒可用 `BakedCarriage.localBounds` 经 `local` 变换得到;kinetic 桶用 section 的 32³ 立方体。

⚠️ **不要试图复用 HiZ 遮挡结果**——它只存在于 GPU,回读路径是给节点淘汰用的 256 项固定表,不是通用查询。

### 3.2 上传节流

**不要挤 `UploadStream.INSTANCE`**(共享 64MB 环、渲染线程独占、耗尽时 `glFinish()` 重试并可能抛出)。Create 远景快照的网格是独立 VBO,自己直接 `glCreateBuffers` + `glNamedBufferData` 即可,与地形几何不共享 arena。

每帧配额自建:`UPLOADS_PER_FRAME`(建议 2)+ `REBAKES_PER_TICK`(kinetic 已有 4,复用同一常量语义)。**不要试图挂到某个统一调度器上——不存在**,现有节流分散在三处互不相关的位置。

---

## 4. 风险与未决

| 风险 | 影响 | 处置 |
|---|---|---|
| P1 的 `build()` 拆分触及所有 Create 远景渲染器 | 高——回归面广 | 拆分后先跑一轮全 Create 功能回归再进 P2 |
| `Snap.generic` 顶点流体积 | kinetic 源数据可能比预期大得多 | P1 落地后先量实际字节数,再决定 kinetic 要不要落盘,或只做内存 LRU |
| 按需重烘的延迟 | 玩家转身时远景机器晚几帧出现 | 由近及远预烘 + 视锥外扩一圈作为预测边距 |
| aux 表体积增长 | 长期游玩后存储膨胀 | 索引记录 `lastSeenGameTime`,提供 `/voxy debug create prune <days>` 手动清理;**不做自动过期**(理由同 §1 P0-1) |

**未决(需实测才能定)**:
1. kinetic 的 `Snap.generic` 实际字节量级——决定 kinetic 是否值得落盘。
2. 掉帧到底主要来自哪一条(Create 半径 / VSS 通道复活 / 船载追踪距离)。**P0 动工前应先拿到测试者的二分结果**——若主因是 VSS,P0 做完帧率也不会回来。
