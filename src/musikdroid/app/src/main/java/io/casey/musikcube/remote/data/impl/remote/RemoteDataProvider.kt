package io.casey.musikcube.remote.data.impl.remote

import io.casey.musikcube.remote.data.*
import io.casey.musikcube.remote.websocket.Messages
import io.casey.musikcube.remote.websocket.SocketMessage
import io.casey.musikcube.remote.websocket.WebSocketService
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.ReplaySubject
import org.json.JSONArray
import org.json.JSONObject

class RemoteDataProvider(private val service: WebSocketService) : IDataProvider {
    private var disposables = CompositeDisposable()
    private var currentState = mapState(service.state)

    private val connectionStatePublisher: ReplaySubject<
        Pair<IDataProvider.State, IDataProvider.State>> = ReplaySubject.createWithSize(1)

    private val playQueueStatePublisher: PublishSubject<Unit> = PublishSubject.create()

    private val authFailurePublisher: PublishSubject<Unit> = PublishSubject.create()

    init {
        disposables.add(observeState().subscribe({ updatedStates ->
            currentState = updatedStates.first
        }, { /*error */ }))
    }

    override val state: IDataProvider.State
        get() = currentState

    override fun getAlbums(filter: String): Observable<List<IAlbum>> {
        return getAlbumsForCategory("", 0, filter)
    }

    override fun getAlbumsForCategory(categoryType: String, categoryId: Long, filter: String): Observable<List<IAlbum>> {
        val message = SocketMessage.Builder
            .request(Messages.Request.QueryAlbums)
            .addOption(Messages.Key.CATEGORY, categoryType)
            .addOption(Messages.Key.CATEGORY_ID, categoryId)
            .addOption(Messages.Key.FILTER, filter)
            .build()

        return service.observe(message, client)
            .flatMap<List<IAlbum>> { socketMessage -> toAlbumList(socketMessage) }
            .compose(applySchedulers())
    }

    override fun getTrackCount(filter: String): Observable<Int> {
        val message = SocketMessage.Builder
            .request(Messages.Request.QueryTracks)
            .addOption(Messages.Key.FILTER, filter)
            .addOption(Messages.Key.COUNT_ONLY, true)
            .build()

        return service.observe(message, client)
            .flatMap<Int> { socketMessage -> toCount(socketMessage) }
            .compose(applySchedulers())
    }

    override fun getTracks(limit: Int, offset: Int, filter: String): Observable<List<ITrack>> {
        val builder = SocketMessage.Builder
            .request(Messages.Request.QueryTracks)
            .addOption(Messages.Key.FILTER, filter)

        if (limit > 0 && offset >= 0) {
            builder.addOption(Messages.Key.LIMIT, limit)
            builder.addOption(Messages.Key.OFFSET, offset)
        }

        return service.observe(builder.build(), client)
            .flatMap<List<ITrack>> { socketMessage -> toTrackList(socketMessage) }
            .compose(applySchedulers())
    }

    override fun getTracks(filter: String): Observable<List<ITrack>> {
        return getTracks(-1, -1, filter)
    }

    override fun getTrackCountByCategory(category: String, id: Long, filter: String): Observable<Int> {
        val message = SocketMessage.Builder
            .request(Messages.Request.QueryTracksByCategory)
            .addOption(Messages.Key.FILTER, filter)
            .addOption(Messages.Key.CATEGORY, category)
            .addOption(Messages.Key.ID, id)
            .addOption(Messages.Key.COUNT_ONLY, true)
            .build()

        return service.observe(message, client)
            .flatMap<Int> { socketMessage -> toCount(socketMessage) }
            .compose(applySchedulers())
    }

    override fun getTracksByCategory(category: String, id: Long, filter: String): Observable<List<ITrack>> {
        return getTracksByCategory(category, id, -1, -1, filter)
    }

    override fun getTracksByCategory(category: String, id: Long, limit: Int, offset: Int, filter: String): Observable<List<ITrack>> {
        val builder = SocketMessage.Builder
            .request(Messages.Request.QueryTracksByCategory)
            .addOption(Messages.Key.FILTER, filter)
            .addOption(Messages.Key.CATEGORY, category)
            .addOption(Messages.Key.ID, id)

        if (limit > 0 && offset >= 0) {
            builder.addOption(Messages.Key.LIMIT, limit)
            builder.addOption(Messages.Key.OFFSET, offset)
        }

        return service.observe(builder.build(), client)
            .flatMap<List<ITrack>> { socketMessage -> toTrackList(socketMessage) }
            .compose(applySchedulers())
    }

    override fun getPlayQueueTracksCount(filter: String): Observable<Int> {
        val message = SocketMessage.Builder
            .request(Messages.Request.QueryPlayQueueTracks)
            .addOption(Messages.Key.FILTER, filter)
            .addOption(Messages.Key.COUNT_ONLY, true)
            .build()

        return service.observe(message, client)
            .flatMap<Int> { socketMessage -> toCount(socketMessage) }
            .compose(applySchedulers())
    }

    override fun getPlayQueueTracks(filter: String): Observable<List<ITrack>> {
        return getPlayQueueTracks(-1, -1, filter)
    }

    override fun getPlayQueueTracks(limit: Int, offset: Int, filter: String): Observable<List<ITrack>> {
        val builder = SocketMessage.Builder
            .request(Messages.Request.QueryPlayQueueTracks)
            .addOption(Messages.Key.FILTER, filter)

        if (limit > 0 && offset >= 0) {
            builder.addOption(Messages.Key.LIMIT, limit)
            builder.addOption(Messages.Key.OFFSET, offset)
        }

        return service.observe(builder.build(), client)
            .flatMap<List<ITrack>> { socketMessage -> toTrackList(socketMessage) }
            .compose(applySchedulers())
    }

    override fun getCategoryValues(type: String, filter: String): Observable<List<ICategoryValue>> {
        val message = SocketMessage.Builder
            .request(Messages.Request.QueryCategory)
            .addOption(Messages.Key.CATEGORY, type)
            .addOption(Messages.Key.FILTER, filter)
            .build()

        return service.observe(message, client)
            .flatMap<List<ICategoryValue>> { socketMessage -> toCategoryList(socketMessage, type) }
            .compose(applySchedulers())
    }

    override fun observeState(): Observable<Pair<IDataProvider.State, IDataProvider.State>> {
        return connectionStatePublisher.compose(applySchedulers())
    }

    override fun observePlayQueue(): Observable<Unit> {
        return playQueueStatePublisher.compose(applySchedulers())
    }

    override fun observeAuthFailure(): Observable<Unit> {
        return authFailurePublisher.compose(applySchedulers())
    }

    override fun attach() {
        service.addClient(client)
    }

    override fun detach() {
        service.cancelMessages(client)
        service.removeClient(client)
    }

    override fun destroy() {
        detach()
        disposables.dispose()
    }

    private val client: WebSocketService.Client = object : WebSocketService.Client {
        override fun onStateChanged(newState: WebSocketService.State, oldState: WebSocketService.State) {
            connectionStatePublisher.onNext(Pair(mapState(newState), mapState(oldState)))
        }

        override fun onMessageReceived(message: SocketMessage) {
            if (message.type == SocketMessage.Type.Broadcast) {
                if (Messages.Broadcast.PlayQueueChanged.matches(message.name)) {
                    playQueueStatePublisher.onNext(Unit)
                }
            }
        }

        override fun onInvalidPassword() {
            authFailurePublisher.onNext(Unit)
        }
    }

    private fun mapState(state: WebSocketService.State): IDataProvider.State {
        return when (state) {
            WebSocketService.State.Disconnected -> IDataProvider.State.Disconnected
            WebSocketService.State.Connecting -> IDataProvider.State.Connecting
            WebSocketService.State.Connected -> IDataProvider.State.Connected
        }
    }

    companion object {
        private fun <T> applySchedulers(): ObservableTransformer<T, T> {
            return ObservableTransformer { upstream ->
                with (upstream) {
                    subscribeOn(AndroidSchedulers.mainThread())
                    observeOn(AndroidSchedulers.mainThread())
                }
                upstream
            }
        }

        private val toAlbumArtist: (JSONObject, String) -> ICategoryValue = { json, type -> RemoteAlbumArtist(json) }
        private val toCategoryValue: (JSONObject, String) -> ICategoryValue = { json, type -> RemoteCategoryValue(type, json) }

        private fun toCategoryList(socketMessage: SocketMessage, type: String): Observable<List<ICategoryValue>> {
            val converter: (JSONObject, String) -> ICategoryValue = when (type) {
                Messages.Category.ALBUM_ARTIST -> toAlbumArtist
                else -> toCategoryValue
            }
            val values = ArrayList<ICategoryValue>()
            val json = socketMessage.getJsonArrayOption(Messages.Key.DATA, JSONArray())!!
            for (i in 0 until json.length()) {
                values.add(converter(json.getJSONObject(i), type))
            }
            return Observable.just(values)
        }

        private fun toTrackList(socketMessage: SocketMessage): Observable<List<ITrack>> {
            val tracks = ArrayList<ITrack>()
            val json = socketMessage.getJsonArrayOption(Messages.Key.DATA, JSONArray())!!
            for (i in 0 until json.length()) {
                tracks.add(RemoteTrack(json.getJSONObject(i)))
            }
            return Observable.just(tracks)
        }

        private fun toAlbumList(socketMessage: SocketMessage): Observable<List<IAlbum>> {
            val albums = ArrayList<IAlbum>()
            val json = socketMessage.getJsonArrayOption(Messages.Key.DATA, JSONArray())!!
            for (i in 0 until json.length()) {
                albums.add(RemoteAlbum(json.getJSONObject(i)))
            }
            return Observable.just(albums)
        }

        private fun toCount(message: SocketMessage): Observable<Int> {
            return Observable.just(message.getIntOption(Messages.Key.COUNT, 0))
        }
    }
}