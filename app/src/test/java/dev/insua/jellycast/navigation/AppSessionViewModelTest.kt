package dev.insua.jellycast.navigation

import android.content.Context
import androidx.media3.common.Player
import dev.insua.jellycast.datastore.LastPlayed
import dev.insua.jellycast.datastore.LastPlayedStore
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.feature.player.PlayerConnection
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.player.AudioPlaybackEngine
import dev.insua.jellycast.player.AutoPlayNextController
import dev.insua.jellycast.player.PlaybackEngineState
import dev.insua.jellycast.player.PlayQueue
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Task 5(冷启动恢复迷你播放条):[AppSessionViewModel] 在构造时(生产是 `init`,这里等价于
 * 一次 `newViewModel()`)就把 [LastPlayedStore] 里的记录直接装填进迷你条,不用先进首页找
 * "继续收听"再点进去。
 *
 * 三条硬约束各有一条对应的用例,并且各自在报告里做过变异验证:
 * - [恢复过程中不发任何网络请求]
 * - [恢复过程中不启动前台服务]
 * - [恢复不会自动播放]
 *
 * 沿用 [dev.insua.jellycast.feature.home.HomeViewModelTest] 的替身风格:JVM 纯 MockK,
 * `StandardTestDispatcher` + `advanceUntilIdle()` 把 `init` 里那几个 `viewModelScope.launch`
 * 都跑到落地。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppSessionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------- 造假

    private fun record(
        itemId: String = "item-1",
        positionMs: Long = 620_000L,
        title: String = "反重力",
        subtitle: String = "第 3 集",
        imageTag: String? = "tag-1",
        runTimeMs: Long? = 1_800_000L,
        kind: String = MediaKind.EPISODE.name,
    ) = LastPlayed(
        itemId = itemId,
        positionMs = positionMs,
        title = title,
        subtitle = subtitle,
        imageTag = imageTag,
        runTimeMs = runTimeMs,
        updatedAt = 0L,
        kind = kind,
    )

    /** 默认没有激活服务器(相当于"退出登录"/冷启动还没连过):[refreshBaseUrl] 不会被触发,
     *  `session` 因此不会被 `init` 里那半段代码碰到——这样"恢复不发网络请求"才测得干净。 */
    private fun fakeServerStore(activeServerId: String? = null): ServerStore {
        val store = mockk<ServerStore>()
        every { store.activeServerId } returns flowOf(activeServerId)
        return store
    }

    private fun fakeEngine(currentItemId: String? = null): AudioPlaybackEngine {
        val engine = mockk<AudioPlaybackEngine>()
        every { engine.state } returns MutableStateFlow(PlaybackEngineState.Idle)
        every { engine.currentItemId } returns currentItemId
        coEvery { engine.play(any(), any(), any()) } returns Unit
        return engine
    }

    private fun fakePlayerConnection(): PlayerConnection {
        val connection = mockk<PlayerConnection>()
        every { connection.nowPlaying } returns MutableStateFlow(null)
        every { connection.player } returns MutableStateFlow(null)
        return connection
    }

    private fun fakeLastPlayedStore(lastPlayed: LastPlayed?): LastPlayedStore {
        val store = mockk<LastPlayedStore>()
        every { store.lastPlayed } returns flowOf(lastPlayed)
        coEvery { store.clear() } returns Unit
        return store
    }

    /** 真实 [AutoPlayNextController]:接一个恒为 `false` 的偏好 flow,不需要摸网络。 */
    private fun autoPlayNextController(playQueue: PlayQueue) =
        AutoPlayNextController(playQueue, mockk<JellyfinApi>(), flowOf(false))

    private fun newViewModel(
        lastPlayed: LastPlayed? = record(),
        serverStore: ServerStore = fakeServerStore(),
        session: JellyfinSession = mockk(),
        playQueue: PlayQueue = PlayQueue(),
        engine: AudioPlaybackEngine = fakeEngine(),
        playerConnection: PlayerConnection = fakePlayerConnection(),
        lastPlayedStore: LastPlayedStore = fakeLastPlayedStore(lastPlayed),
        context: Context = mockk(relaxed = true),
    ): AppSessionViewModel = AppSessionViewModel(
        serverStore = serverStore,
        session = session,
        playQueue = playQueue,
        audioPlaybackEngine = engine,
        autoPlayNextController = autoPlayNextController(playQueue),
        playerConnection = playerConnection,
        lastPlayedStore = lastPlayedStore,
        context = context,
    )

    // ---------------------------------------------------------------- 用例

    @Test
    fun `有记录时冷启动后miniPlayer非空,标题副标题进度与记录一致,isPlaying为false`() = runTest(testDispatcher) {
        val r = record(positionMs = 900_000L, runTimeMs = 1_800_000L, title = "反重力", subtitle = "第 3 集")
        val viewModel = newViewModel(lastPlayed = r)

        advanceUntilIdle()

        val state = viewModel.miniPlayer.value
        assertNotNull(state, "有本地记录时冷启动后迷你条必须非空,不能等用户先进首页找继续收听")
        assertEquals(r.title, state!!.title)
        assertEquals(r.subtitle, state.subtitle)
        assertEquals(miniPlayerProgress(r.positionMs, r.runTimeMs), state.progress)
        assertFalse(state.isPlaying, "恢复出来的状态绝不能自称正在播放")
        assertFalse(state.hasNext, "恢复出来的单条目队列没有下一条")
    }

    @Test
    fun `恢复过程中不发任何网络请求`() = runTest(testDispatcher) {
        val session = mockk<JellyfinSession>()
        val viewModel = newViewModel(session = session)

        advanceUntilIdle()

        assertNotNull(viewModel.miniPlayer.value, "前提:确实发生了一次恢复,否则下面的零调用断言没有意义")
        verify { session wasNot Called }
    }

    @Test
    fun `恢复过程中不启动前台服务`() = runTest(testDispatcher) {
        val context = mockk<Context>()
        val viewModel = newViewModel(context = context)

        advanceUntilIdle()

        assertNotNull(viewModel.miniPlayer.value, "前提:确实发生了一次恢复,否则下面的零调用断言没有意义")
        verify { context wasNot Called }
    }

    @Test
    fun `恢复不会自动播放`() = runTest(testDispatcher) {
        val engine = fakeEngine()
        val viewModel = newViewModel(engine = engine)

        advanceUntilIdle()

        assertNotNull(viewModel.miniPlayer.value, "前提:确实发生了一次恢复,否则下面的零调用断言没有意义")
        coVerify(exactly = 0) { engine.play(any(), any(), any()) }
    }

    @Test
    fun `没有记录时miniPlayer保持null`() = runTest(testDispatcher) {
        val viewModel = newViewModel(lastPlayed = null)

        advanceUntilIdle()

        assertNull(viewModel.miniPlayer.value)
    }

    @Test
    fun `恢复出来的条目,点播放时起点是记录里的位置`() = runTest(testDispatcher) {
        val r = record(itemId = "item-9", positionMs = 725_000L)
        val session = mockk<JellyfinSession>()
        coEvery { session.userId() } returns "user-1"
        // play() 成功之后会顺带 refreshBaseUrl() 一次(既有行为,和这条用例的断言无关,但要让它
        // 走得通,不被一次未桩的调用炸出 MockKException):baseUrl() 走 runCatching 会被吞掉,
        // cachedBaseUrlOrNull() 没有包 runCatching,必须显式给一个答案。
        coEvery { session.baseUrl() } throws IllegalStateException("not stubbed for this test")
        every { session.cachedBaseUrlOrNull() } returns null
        val engine = fakeEngine(currentItemId = null)

        val viewModel = newViewModel(lastPlayed = r, session = session, engine = engine)
        advanceUntilIdle()

        viewModel.onMiniPlayerPlayPause()
        advanceUntilIdle()

        coVerify { engine.play("item-9", "user-1", 725_000L) }
    }

    /**
     * [onMiniPlayerPlayPause] 的判据必须是引擎有没有当前条目([AudioPlaybackEngine.currentItemId]),
     * 不能是 `isPlaying`——一个被用户手动暂停的真实会话同样 `isPlaying == false`,如果误判成
     * "恢复出来但还没开始播"而重新调 [play],会把用户暂停的位置弄丢、还发一次多余的播放请求。
     */
    @Test
    fun `暂停中的真实会话,点播放暂停按钮切换播放状态而不是重新起播`() = runTest(testDispatcher) {
        val engine = fakeEngine(currentItemId = "item-live")
        val pausedPlayer = mockk<Player>(relaxed = true)
        every { pausedPlayer.isPlaying } returns false
        val connection = mockk<PlayerConnection>()
        every { connection.nowPlaying } returns MutableStateFlow(null)
        every { connection.player } returns MutableStateFlow(pausedPlayer)

        val viewModel = newViewModel(engine = engine, playerConnection = connection)
        advanceUntilIdle()

        viewModel.onMiniPlayerPlayPause()
        advanceUntilIdle()

        verify { pausedPlayer.play() }
        coVerify(exactly = 0) { engine.play(any(), any(), any()) }
    }

    // ---- 复审 Task 5 Important 2:kind 必须按记录还原,不能固定成 EPISODE ----
    // 之前这里硬编码 MediaKind.EPISODE,并声称"播放开始后会被真实数据覆盖"——这句话是假的,
    // AudioPlaybackEngineImpl.play() 根本不碰 PlayQueue。电影是一等公民,恢复出来的电影如果
    // 被误判成剧集,播完时 AutoPlayNextController 会去找"下一集"而不是判定为整部片子播完。

    @Test
    fun `恢复出来的条目,kind 按记录里的值还原成MOVIE,不再固定成EPISODE`() = runTest(testDispatcher) {
        val queue = PlayQueue()
        val r = record(kind = MediaKind.MOVIE.name)
        newViewModel(lastPlayed = r, playQueue = queue)

        advanceUntilIdle()

        assertEquals(MediaKind.MOVIE, queue.current.value?.kind, "恢复出来的电影不能被误判成剧集")
    }

    @Test
    fun `记录里kind是无法识别的值时降级成EPISODE而不是崩溃`() = runTest(testDispatcher) {
        val queue = PlayQueue()
        val r = record(kind = "这不是一个合法的MediaKind")
        newViewModel(lastPlayed = r, playQueue = queue)

        advanceUntilIdle()

        assertEquals(MediaKind.EPISODE, queue.current.value?.kind, "无法识别的 kind 应该静默降级,不影响冷启动")
    }

    // ---- 复审 Task 5 Important 1:换服务器必须重置进程内已经装填好的迷你条/播放队列 ----
    // 复现路径:设置 → 管理服务器 → 删除当前活跃服务器 → 连一台新服务器 → 回首页。之前
    // onServerConnected() 只清了 LastPlayedStore 这一份磁盘记录,init 里 restoreLastPlayed()
    // 已经灌进 _miniPlayer / playQueue 的内存状态完全没被碰——迷你条会继续显示旧服务器那一集,
    // 点播放会把旧服务器的 itemId 发给刚连上的新服务器。

    @Test
    fun `onServerConnected清空进程内迷你条与播放队列,不让旧服务器的恢复状态survive`() = runTest(testDispatcher) {
        val queue = PlayQueue()
        // relaxed:onServerConnected() 顺带调用的 refreshBaseUrl() 会摸 session.cachedBaseUrlOrNull()
        // (没有包 runCatching),跟这条用例要断言的东西无关,只是要让它走得通。
        val viewModel = newViewModel(playQueue = queue, session = mockk(relaxed = true))
        advanceUntilIdle()
        assertNotNull(viewModel.miniPlayer.value, "前提:确实发生过一次恢复")
        assertNotNull(queue.current.value, "前提:队列里确实有恢复出来的条目")

        viewModel.onServerConnected()
        advanceUntilIdle()

        assertNull(viewModel.miniPlayer.value, "换了服务器之后,旧服务器的迷你条不能继续显示")
        assertNull(queue.current.value, "换了服务器之后,旧条目不能继续留在队列里,否则点播放会把新服务器的请求发给旧 itemId")
    }

    @Test
    fun `onServerConnected清掉持久化的上次播放记录`() = runTest(testDispatcher) {
        val lastPlayedStore = fakeLastPlayedStore(record())
        val viewModel = newViewModel(lastPlayedStore = lastPlayedStore, session = mockk(relaxed = true))
        advanceUntilIdle()

        viewModel.onServerConnected()
        advanceUntilIdle()

        coVerify { lastPlayedStore.clear() }
    }
}
