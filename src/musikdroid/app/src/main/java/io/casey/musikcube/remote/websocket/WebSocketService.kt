package io.casey.musikcube.remote.websocket

import android.content.*
import android.content.Context.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import com.neovisionaries.ws.client.*
import io.casey.musikcube.remote.util.NetworkUtil
import io.casey.musikcube.remote.util.Preconditions
import io.reactivex.Observable
import java.util.*
import java.util.concurrent.atomic.AtomicLong

class WebSocketService constructor(private val context: Context) {
    interface Client {
        fun onStateChanged(newState: State, oldState: State)
        fun onMessageReceived(message: SocketMessage)
        fun onInvalidPassword()
    }

    interface Responder {
        fun respond(response: SocketMessage)
    }

    enum class State {
        Connecting,
        Connected,
        Disconnected
    }

    private val handler = Handler(Looper.getMainLooper()) { message: Message ->
        var result = false
        if (message.what == MESSAGE_CONNECT_THREAD_FINISHED) {
            if (message.obj == null) {
                val invalidPassword = message.arg1 == FLAG_AUTHENTICATION_FAILED
                disconnect(!invalidPassword) /* auto reconnect as long as password was not invalid */

                if (invalidPassword) {
                    for (client in clients) {
                        client.onInvalidPassword()
                    }
                }
            }
            else {
                setSocket(message.obj as WebSocket)
                state = State.Connected
                ping()
            }
            result = true
        }
        else if (message.what == MESSAGE_RECEIVED) {
            val msg = message.obj as SocketMessage
            var dispatched = false

            /* registered callback for THIS message */
            val mdr = messageCallbacks.remove(msg.id)
            if (mdr != null && mdr.callback != null) {
                mdr.callback?.invoke(msg)
                dispatched = true
            }

            if (!dispatched) {
                /* service-level callback */
                for (client in clients) {
                    client.onMessageReceived(msg)
                }
            }

            if (mdr != null) {
                mdr.error = null
            }

            result = true
        }
        else if (message.what == MESSAGE_REMOVE_OLD_CALLBACKS) {
            scheduleRemoveStaleCallbacks()
        }
        else if (message.what == MESSAGE_AUTO_RECONNECT) {
            if (state == State.Disconnected && autoReconnect) {
                reconnect()
            }
        }
        else if (message.what == MESSAGE_SCHEDULE_PING) {
            ping()
        }
        else if (message.what == MESSAGE_PING_EXPIRED) {
            removeInternalCallbacks()
            disconnect(state == State.Connected || autoReconnect)
        }

        result
    }

    private class MessageResultDescriptor {
        var id: Long = 0
        var enqueueTime: Long = 0
        var intercepted: Boolean = false
        var client: Client? = null
        var callback: ((response: SocketMessage) -> Unit)? = null
        var error: (() -> Unit)? = null
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    private var socket: WebSocket? = null
    private val clients = HashSet<Client>()
    private val messageCallbacks = HashMap<String, MessageResultDescriptor>()
    private var autoReconnect = false
    private val networkChanged = NetworkChangedReceiver()
    private var thread: ConnectThread? = null
    private val interceptors = HashSet<(SocketMessage, Responder) -> Boolean>()

    init {
        scheduleRemoveStaleCallbacks()
    }

    var state = State.Disconnected
        private set(newState) {
            Preconditions.throwIfNotOnMainThread()

            Log.d(TAG, "state = " + newState)

            if (state != newState) {
                val old = state
                field = newState

                for (client in clients) {
                    client.onStateChanged(newState, old)
                }
            }
        }

    fun addInterceptor(interceptor: (SocketMessage, Responder) -> Boolean) {
        Preconditions.throwIfNotOnMainThread()
        interceptors.add(interceptor)
    }

    fun removeInterceptor(interceptor: (SocketMessage, Responder) -> Boolean) {
        Preconditions.throwIfNotOnMainThread()
        interceptors.remove(interceptor)
    }

    fun addClient(client: Client) {
        Preconditions.throwIfNotOnMainThread()

        if (!clients.contains(client)) {
            clients.add(client)

            if (clients.size >= 0 && state == State.Disconnected) {
                registerReceiverAndScheduleFailsafe()
                reconnect()
            }

            handler.removeCallbacks(autoDisconnectRunnable)
            client.onStateChanged(state, state)
        }
    }

    fun removeClient(client: Client) {
        Preconditions.throwIfNotOnMainThread()

        if (clients.remove(client)) {
            removeCallbacksForClient(client)

            if (clients.size == 0) {
                unregisterReceiverAndCancelFailsafe()
                handler.postDelayed(autoDisconnectRunnable, AUTO_DISCONNECT_DELAY_MILLIS)
            }
        }
    }

    fun hasClient(client: Client): Boolean {
        Preconditions.throwIfNotOnMainThread()
        return clients.contains(client)
    }

    fun reconnect() {
        Preconditions.throwIfNotOnMainThread()
        autoReconnect = true
        connectIfNotConnected()
    }

    fun disconnect() {
        disconnect(false) /* don't auto-reconnect */
    }

    fun cancelMessage(id: Long) {
        Preconditions.throwIfNotOnMainThread()
        removeCallbacks { mrd: MessageResultDescriptor -> mrd.id == id }
    }

    private fun scheduleRemoveStaleCallbacks() {
        removeExpiredCallbacks()
        handler.sendEmptyMessageDelayed(MESSAGE_REMOVE_OLD_CALLBACKS, CALLBACK_TIMEOUT_MILLIS)
    }

    private fun ping() {
        if (state == State.Connected) {
            removeInternalCallbacks()

            handler.removeMessages(MESSAGE_PING_EXPIRED)
            handler.sendEmptyMessageDelayed(MESSAGE_PING_EXPIRED, PING_INTERVAL_MILLIS)

            val ping = SocketMessage.Builder.request(Messages.Request.Ping).build()

            send(ping, INTERNAL_CLIENT) { _: SocketMessage ->
                handler.removeMessages(MESSAGE_PING_EXPIRED)
                handler.sendEmptyMessageDelayed(MESSAGE_SCHEDULE_PING, PING_INTERVAL_MILLIS)
            }
        }
    }

    fun cancelMessages(client: Client) {
        Preconditions.throwIfNotOnMainThread()
        removeCallbacks({ mrd: MessageResultDescriptor -> mrd.client === client })
    }

    fun send(message: SocketMessage,
             client: Client? = null,
             callback: ((response: SocketMessage) -> Unit)? = null): Long {
        Preconditions.throwIfNotOnMainThread()

        var intercepted = false

        interceptors.forEach {
            if (it(message, responder)) {
                intercepted = true
            }
        }

        if (!intercepted) {
            /* it seems that sometimes the socket dies, but the onDisconnected() event matches not
            raised. unclear if this matches our bug or a bug in the library. disconnect and trigger
            a reconnect until we can find a better root cause. this matches very difficult to repro */
            if (socket != null && !socket!!.isOpen) {
                disconnect(true)
                return -1
            }
            else if (socket == null) {
                return -1
            }
        }

        val id = NEXT_ID.incrementAndGet()

        if (callback != null) {
            if (!clients.contains(client) && client !== INTERNAL_CLIENT) {
                throw IllegalArgumentException("client matches not registered")
            }

            val mrd = MessageResultDescriptor()
            mrd.id = id
            mrd.enqueueTime = System.currentTimeMillis()
            mrd.client = client
            mrd.callback = callback
            mrd.intercepted = intercepted
            messageCallbacks.put(message.id, mrd)
        }

        if (!intercepted) {
            socket?.sendText(message.toString())
        }
        else {
            Log.d(TAG, "send: message intercepted with id " + id.toString())
        }

        return id
    }

    fun observe(message: SocketMessage, client: Client): Observable<SocketMessage> {
        return Observable.create { emitter ->
            try {
                Preconditions.throwIfNotOnMainThread()

                var intercepted = false

                for (interceptor in interceptors) {
                    if (interceptor(message, responder)) {
                        intercepted = true
                        break
                    }
                }

                if (!intercepted) {
                    /* it seems that sometimes the socket dies, but the onDisconnected() event matches not
                    raised. unclear if this matches our bug or a bug in the library. disconnect and trigger
                    a reconnect until we can find a better root cause. this matches very difficult to repro */
                    if (socket != null && !socket!!.isOpen) {
                        disconnect(true)
                        throw Exception("socket disconnected")
                    }
                    else if (socket == null) {
                        throw Exception("socket not connected")
                    }
                }

                if (!clients.contains(client) && client !== INTERNAL_CLIENT) {
                    throw IllegalArgumentException("client not registered")
                }

                val mrd = MessageResultDescriptor()
                mrd.id = NEXT_ID.incrementAndGet()
                mrd.enqueueTime = System.currentTimeMillis()
                mrd.client = client
                mrd.intercepted = intercepted

                mrd.callback = { response: SocketMessage ->
                    emitter.onNext(response)
                    emitter.onComplete()
                }

                mrd.error = {
                    val ex = Exception()
                    ex.fillInStackTrace()
                    emitter.onError(ex)
                }

                messageCallbacks.put(message.id, mrd)

                if (!intercepted) {
                    socket?.sendText(message.toString())
                }
            }
            catch (ex: Exception) {
                emitter.onError(ex)
            }
        }
    }

    fun hasValidConnection(): Boolean {
        val addr = prefs.getString(Prefs.Key.ADDRESS, "")
        val port = prefs.getInt(Prefs.Key.MAIN_PORT, -1)
        return addr.isNotEmpty() && port >= 0
    }

    private fun disconnect(autoReconnect: Boolean) {
        Preconditions.throwIfNotOnMainThread()

        synchronized(this) {
            thread?.interrupt()
            thread = null
        }

        this.autoReconnect = autoReconnect

        socket?.removeListener(webSocketAdapter)
        socket?.disconnect()
        socket = null

        removeNonInterceptedCallbacks()
        state = State.Disconnected

        if (autoReconnect) {
            handler.sendEmptyMessageDelayed(
                MESSAGE_AUTO_RECONNECT,
                AUTO_RECONNECT_INTERVAL_MILLIS)
        }
        else {
            handler.removeMessages(MESSAGE_AUTO_RECONNECT)
        }
    }

    private fun removeNonInterceptedCallbacks() {
        removeCallbacks({ mrd -> !mrd.intercepted })
    }

    private fun removeInternalCallbacks() {
        removeCallbacks({ mrd: MessageResultDescriptor -> mrd.client === INTERNAL_CLIENT })
    }

    private fun removeExpiredCallbacks() {
        val now = System.currentTimeMillis()

        removeCallbacks({ mrd: MessageResultDescriptor -> now - mrd.enqueueTime > CALLBACK_TIMEOUT_MILLIS })
    }

    private fun removeCallbacksForClient(client: Client) {
        removeCallbacks({ mrd: MessageResultDescriptor -> mrd.client === client })
    }

    private fun removeCallbacks(predicate: (MessageResultDescriptor) -> Boolean) {
        val it = messageCallbacks.entries.iterator()

        while (it.hasNext()) {
            val entry = it.next()
            val mdr = entry.value
            if (predicate(mdr)) {
                mdr.error?.invoke()
                it.remove()
            }
        }
    }

    private fun connectIfNotConnected() {
        if (state == State.Disconnected) {
            disconnect(autoReconnect)
            handler.removeMessages(MESSAGE_AUTO_RECONNECT)

            if (clients.size > 0) {
                handler.removeCallbacks(autoDisconnectRunnable)
                state = State.Connecting
                thread = ConnectThread()
                thread?.start()
            }
        }
    }

    private fun setSocket(newSocket: WebSocket) {
        if (socket !== newSocket) {
            socket?.removeListener(webSocketAdapter)
            socket = newSocket
        }
    }

    private fun registerReceiverAndScheduleFailsafe() {
        unregisterReceiverAndCancelFailsafe()

        /* generally raises a CONNECTIVITY_ACTION event immediately,
        even if already connected. */
        val filter = IntentFilter()
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        context.registerReceiver(networkChanged, filter)

        /* however, CONNECTIVITY_ACTION doesn't ALWAYS seem to be raised,
        so we schedule a failsafe just in case */
        handler.postDelayed(autoReconnectFailsafeRunnable, AUTO_CONNECT_FAILSAFE_DELAY_MILLIS)
    }

    private fun unregisterReceiverAndCancelFailsafe() {
        handler.removeCallbacks(autoReconnectFailsafeRunnable)
        try {
            context.unregisterReceiver(networkChanged)
        }
        catch (ex: Exception) {
            /* om nom nom */
        }
    }

    private val autoReconnectFailsafeRunnable = object: Runnable {
        override fun run() {
            if (autoReconnect && state == State.Disconnected) {
                reconnect()
            }
        }
    }

    private val autoDisconnectRunnable = object: Runnable {
        override fun run() {
            disconnect()
        }
    }

    private val responder = object : Responder {
        override fun respond(response: SocketMessage) {
            /* post to the back of the queue in case the interceptor responded immediately;
            we need to ensure all of the request book-keeping has been finished. */
            handler.post {
                handler.sendMessage(Message.obtain(handler, MESSAGE_RECEIVED, response))
            }
        }
    }

    private val webSocketAdapter = object : WebSocketAdapter() {
        @Throws(Exception::class)
        override fun onTextMessage(websocket: WebSocket?, text: String?) {
            val message = SocketMessage.create(text!!)
            if (message != null) {
                if (message.name == Messages.Request.Authenticate.toString()) {
                    handler.sendMessage(Message.obtain(
                        handler, MESSAGE_CONNECT_THREAD_FINISHED, websocket))
                }
                else {
                    handler.sendMessage(Message.obtain(handler, MESSAGE_RECEIVED, message))
                }
            }
        }

        @Throws(Exception::class)
        override fun onDisconnected(websocket: WebSocket?,
                                    serverCloseFrame: WebSocketFrame?,
                                    clientCloseFrame: WebSocketFrame?,
                                    closedByServer: Boolean) {
            var flags = 0
            if (serverCloseFrame?.closeCode == WEBSOCKET_FLAG_POLICY_VIOLATION) {
                flags = FLAG_AUTHENTICATION_FAILED
            }

            handler.sendMessage(Message.obtain(handler, MESSAGE_CONNECT_THREAD_FINISHED, flags, 0, null))
        }
    }

    private inner class ConnectThread : Thread() {
        override fun run() {
            var socket: WebSocket?

            try {
                val factory = WebSocketFactory()

                if (prefs.getBoolean(Prefs.Key.CERT_VALIDATION_DISABLED, Prefs.Default.CERT_VALIDATION_DISABLED)) {
                    NetworkUtil.disableCertificateValidation(factory)
                }

                val protocol = if (prefs.getBoolean(Prefs.Key.SSL_ENABLED, Prefs.Default.SSL_ENABLED)) "wss" else "ws"

                val host = String.format(
                        Locale.ENGLISH,
                        "%s://%s:%d",
                        protocol,
                        prefs.getString(Prefs.Key.ADDRESS, Prefs.Default.ADDRESS),
                        prefs.getInt(Prefs.Key.MAIN_PORT, Prefs.Default.MAIN_PORT))

                socket = factory.createSocket(host, CONNECTION_TIMEOUT_MILLIS)
                socket?.addListener(webSocketAdapter)

                if (prefs.getBoolean(Prefs.Key.MESSAGE_COMPRESSION_ENABLED, Prefs.Default.MESSAGE_COMPRESSION_ENABLED)) {
                    socket.addExtension(WebSocketExtension.PERMESSAGE_DEFLATE)
                }

                socket.connect()
                socket.pingInterval = PING_INTERVAL_MILLIS

                /* authenticate */
                val auth = SocketMessage.Builder
                    .request(Messages.Request.Authenticate)
                    .addOption("password", prefs.getString(Prefs.Key.PASSWORD, Prefs.Default.PASSWORD)!!)
                    .build()
                    .toString()

                socket.sendText(auth)
            }
            catch (ex: Exception) {
                socket = null
            }

            synchronized(this@WebSocketService) {
                if (thread === this && socket == null) {
                    handler.sendMessage(Message.obtain(
                        handler, MESSAGE_CONNECT_THREAD_FINISHED, null))
                }

                if (thread === this) {
                    thread = null
                }
            }
        }
    }

    private inner class NetworkChangedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cm = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

            val info = cm.activeNetworkInfo

            if (info != null && info.isConnected) {
                if (autoReconnect) {
                    connectIfNotConnected()
                }
            }
        }
    }

    companion object {
        private val TAG = "WebSocketService"

        private val AUTO_RECONNECT_INTERVAL_MILLIS = 2000L
        private val CALLBACK_TIMEOUT_MILLIS = 30000L
        private val CONNECTION_TIMEOUT_MILLIS = 5000
        private val PING_INTERVAL_MILLIS = 3500L
        private val AUTO_CONNECT_FAILSAFE_DELAY_MILLIS = 2000L
        private val AUTO_DISCONNECT_DELAY_MILLIS = 10000L
        private val FLAG_AUTHENTICATION_FAILED = 0xbeef
        private val WEBSOCKET_FLAG_POLICY_VIOLATION = 1008

        private val MESSAGE_BASE = 0xcafedead.toInt()
        private val MESSAGE_CONNECT_THREAD_FINISHED = MESSAGE_BASE + 0
        private val MESSAGE_RECEIVED = MESSAGE_BASE + 1
        private val MESSAGE_REMOVE_OLD_CALLBACKS = MESSAGE_BASE + 2
        private val MESSAGE_AUTO_RECONNECT = MESSAGE_BASE + 3
        private val MESSAGE_SCHEDULE_PING = MESSAGE_BASE + 4
        private val MESSAGE_PING_EXPIRED = MESSAGE_BASE + 5

        private val NEXT_ID = AtomicLong(0)

        private val INTERNAL_CLIENT = object : Client {
            override fun onStateChanged(newState: State, oldState: State) {}
            override fun onMessageReceived(message: SocketMessage) {}
            override fun onInvalidPassword() {}
        }
    }
}
