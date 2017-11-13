package io.casey.musikcube.remote.ui.activity

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.support.v7.app.AlertDialog
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import com.uacf.taskrunner.Task
import com.uacf.taskrunner.Tasks
import io.casey.musikcube.remote.Application
import io.casey.musikcube.remote.R
import io.casey.musikcube.remote.db.connections.Connection
import io.casey.musikcube.remote.playback.PlayerWrapper
import io.casey.musikcube.remote.playback.StreamProxy
import io.casey.musikcube.remote.ui.extension.*
import io.casey.musikcube.remote.websocket.Prefs
import io.casey.musikcube.remote.websocket.WebSocketService
import java.util.*
import io.casey.musikcube.remote.websocket.Prefs.Default as Defaults
import io.casey.musikcube.remote.websocket.Prefs.Key as Keys

class SettingsActivity : WebSocketActivityBase() {
    private lateinit var addressText: EditText
    private lateinit var portText: EditText
    private lateinit var httpPortText: EditText
    private lateinit var passwordText: EditText
    private lateinit var albumArtCheckbox: CheckBox
    private lateinit var messageCompressionCheckbox: CheckBox
    private lateinit var softwareVolume: CheckBox
    private lateinit var sslCheckbox: CheckBox
    private lateinit var certCheckbox: CheckBox
    private lateinit var bitrateSpinner: Spinner
    private lateinit var cacheSpinner: Spinner
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        component.inject(this)
        super.onCreate(savedInstanceState)
        prefs = this.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        setContentView(R.layout.activity_settings)
        setTitle(R.string.settings_title)
        cacheViews()
        bindListeners()
        rebindUi()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.action_save -> {
                save()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == CONNECTIONS_REQUEST_CODE && resultCode == RESULT_OK) {
            if (data != null) {
                val connection = data.getParcelableExtra<Connection>(
                    ConnectionsActivity.EXTRA_SELECTED_CONNECTION)

                if (connection != null) {
                    addressText.setText(connection.hostname)
                    passwordText.setText(connection.password)
                    portText.setText(connection.wssPort.toString())
                    httpPortText.setText(connection.httpPort.toString())
                    sslCheckbox.setCheckWithoutEvent(connection.ssl, sslCheckChanged)
                    certCheckbox.setCheckWithoutEvent(connection.noValidate, certValidationChanged)
                }
            }
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun rebindUi() {
        /* connection info */
        addressText.setTextAndMoveCursorToEnd(prefs.getString(Keys.ADDRESS, Defaults.ADDRESS))
        
        portText.setTextAndMoveCursorToEnd(String.format(
            Locale.ENGLISH, "%d", prefs.getInt(Keys.MAIN_PORT, Defaults.MAIN_PORT)))

        httpPortText.setTextAndMoveCursorToEnd(String.format(
            Locale.ENGLISH, "%d", prefs.getInt(Keys.AUDIO_PORT, Defaults.AUDIO_PORT)))

        passwordText.setTextAndMoveCursorToEnd(prefs.getString(Keys.PASSWORD, Defaults.PASSWORD))

        /* bitrate */
        val bitrates = ArrayAdapter.createFromResource(
            this, R.array.transcode_bitrate_array, android.R.layout.simple_spinner_item)

        bitrates.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        bitrateSpinner.adapter = bitrates
        bitrateSpinner.setSelection(prefs.getInt(
            Keys.TRANSCODER_BITRATE_INDEX, Defaults.TRANSCODER_BITRATE_INDEX))

        val cacheSizes = ArrayAdapter.createFromResource(
            this, R.array.disk_cache_array, android.R.layout.simple_spinner_item)

        /* disk cache */
        cacheSizes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        cacheSpinner.adapter = cacheSizes
        cacheSpinner.setSelection(prefs.getInt(
            Keys.DISK_CACHE_SIZE_INDEX, Defaults.DISK_CACHE_SIZE_INDEX))

        /* advanced */
        albumArtCheckbox.isChecked = prefs.getBoolean(
            Keys.ALBUM_ART_ENABLED, Defaults.ALBUM_ART_ENABLED)
        
        messageCompressionCheckbox.isChecked = prefs.getBoolean(
            Keys.MESSAGE_COMPRESSION_ENABLED, Defaults.MESSAGE_COMPRESSION_ENABLED)
        
        softwareVolume.isChecked = prefs.getBoolean(Keys.SOFTWARE_VOLUME, Defaults.SOFTWARE_VOLUME)

        sslCheckbox.setCheckWithoutEvent(
            this.prefs.getBoolean(Keys.SSL_ENABLED,Defaults.SSL_ENABLED), sslCheckChanged)

        certCheckbox.setCheckWithoutEvent(
            this.prefs.getBoolean(Keys.CERT_VALIDATION_DISABLED, Defaults.CERT_VALIDATION_DISABLED),
            certValidationChanged)

        enableUpNavigation()
    }

    private fun onDisableSslFromDialog() {
        sslCheckbox.setCheckWithoutEvent(false, sslCheckChanged)
    }

    private fun onDisableCertValidationFromDialog() {
        certCheckbox.setCheckWithoutEvent(false, certValidationChanged)
    }

    private val sslCheckChanged = { _: CompoundButton, value:Boolean ->
        if (value) {
            if (!dialogVisible(SslAlertDialog.TAG)) {
                showDialog(SslAlertDialog.newInstance(), SslAlertDialog.TAG)
            }
        }
    }

    private val certValidationChanged = { _: CompoundButton, value: Boolean ->
        if (value) {
            if (!dialogVisible(DisableCertValidationAlertDialog.TAG)) {
                showDialog(
                    DisableCertValidationAlertDialog.newInstance(),
                    DisableCertValidationAlertDialog.TAG)
            }
        }
    }

    private fun cacheViews() {
        this.addressText = findViewById(R.id.address)
        this.portText = findViewById(R.id.port)
        this.httpPortText = findViewById(R.id.http_port)
        this.passwordText = findViewById(R.id.password)
        this.albumArtCheckbox = findViewById(R.id.album_art_checkbox)
        this.messageCompressionCheckbox = findViewById(R.id.message_compression)
        this.softwareVolume = findViewById(R.id.software_volume)
        this.bitrateSpinner = findViewById(R.id.transcoder_bitrate_spinner)
        this.cacheSpinner = findViewById(R.id.streaming_disk_cache_spinner)
        this.sslCheckbox = findViewById(R.id.ssl_checkbox)
        this.certCheckbox = findViewById(R.id.cert_validation)
    }

    private fun bindListeners() {
        findViewById<View>(R.id.button_save_as).setOnClickListener{_ ->
            showSaveAsDialog()
        }

        findViewById<View>(R.id.button_load).setOnClickListener{_ ->
            startActivityForResult(
                ConnectionsActivity.getStartIntent(this),
                CONNECTIONS_REQUEST_CODE)
        }
    }

    private fun showSaveAsDialog() {
        if (!dialogVisible(SaveAsDialog.TAG)) {
            showDialog(SaveAsDialog.newInstance(), SaveAsDialog.TAG)
        }
    }

    private fun showInvalidConnectionDialog(messageId: Int = R.string.settings_invalid_connection_message) {
        if (!dialogVisible(InvalidConnectionDialog.TAG)) {
            showDialog(InvalidConnectionDialog.newInstance(messageId), InvalidConnectionDialog.TAG)
        }
    }

    private fun saveAs(name: String) {
        try {
            val connection = Connection()
            connection.name = name
            connection.hostname = addressText.text.toString()
            connection.wssPort = portText.text.toString().toInt()
            connection.httpPort = httpPortText.text.toString().toInt()
            connection.password = passwordText.text.toString()
            connection.ssl = sslCheckbox.isChecked
            connection.noValidate = certCheckbox.isChecked

            if (connection.valid) {
                runner.run(SaveAsTask.nameFor(connection), SaveAsTask(connection))
            }
            else {
                showInvalidConnectionDialog()
            }
        }
        catch (ex: NumberFormatException) {
            showInvalidConnectionDialog()
        }
    }

    private fun save() {
        val addr = addressText.text.toString()
        val port = portText.text.toString()
        val httpPort = httpPortText.text.toString()
        val password = passwordText.text.toString()

        try {
            prefs.edit()
                    .putString(Keys.ADDRESS, addr)
                    .putInt(Keys.MAIN_PORT, if (port.isNotEmpty()) port.toInt() else 0)
                    .putInt(Keys.AUDIO_PORT, if (httpPort.isNotEmpty()) httpPort.toInt() else 0)
                    .putString(Keys.PASSWORD, password)
                    .putBoolean(Keys.ALBUM_ART_ENABLED, albumArtCheckbox.isChecked)
                    .putBoolean(Keys.MESSAGE_COMPRESSION_ENABLED, messageCompressionCheckbox.isChecked)
                    .putBoolean(Keys.SOFTWARE_VOLUME, softwareVolume.isChecked)
                    .putBoolean(Keys.SSL_ENABLED, sslCheckbox.isChecked)
                    .putBoolean(Keys.CERT_VALIDATION_DISABLED, certCheckbox.isChecked)
                    .putInt(Keys.TRANSCODER_BITRATE_INDEX, bitrateSpinner.selectedItemPosition)
                    .putInt(Keys.DISK_CACHE_SIZE_INDEX, cacheSpinner.selectedItemPosition)
                    .apply()

            if (!softwareVolume.isChecked) {
                PlayerWrapper.setVolume(1.0f)
            }

            StreamProxy.reload()
            wss.disconnect()

            finish()
        }
        catch (ex: NumberFormatException) {
            showInvalidConnectionDialog(R.string.settings_invalid_connection_no_name_message)
        }
    }

    override fun onTaskCompleted(taskName: String, taskId: Long, task: Task<*, *>, result: Any) {
        if (SaveAsTask.match(taskName)) {
            if ((result as SaveAsTask.Result) == SaveAsTask.Result.Exists) {
                val connection = (task as SaveAsTask).connection
                if (!dialogVisible(ConfirmOverwiteDialog.TAG)) {
                    showDialog(
                        ConfirmOverwiteDialog.newInstance(connection),
                        ConfirmOverwiteDialog.TAG)
                }
            }
            else {
                showSnackbar(
                    findViewById<View>(android.R.id.content),
                    R.string.snackbar_saved_connection_preset)
            }
        }
    }

    class SslAlertDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val dlg = AlertDialog.Builder(activity)
                .setTitle(R.string.settings_ssl_dialog_title)
                .setMessage(R.string.settings_ssl_dialog_message)
                .setPositiveButton(R.string.button_enable, null)
                .setNegativeButton(R.string.button_disable) { _, _ ->
                    (activity as SettingsActivity).onDisableSslFromDialog()
                }
                .setNeutralButton(R.string.button_learn_more) { _, _ ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LEARN_MORE_URL))
                        startActivity(intent)
                    }
                    catch (ex: Exception) {
                    }
                }
                .create()

            dlg.setCancelable(false)
            return dlg
        }

        companion object {
            private val LEARN_MORE_URL = "https://github.com/clangen/musikcube/wiki/ssl-server-setup"
            val TAG = "ssl_alert_dialog_tag"

            fun newInstance(): SslAlertDialog {
                return SslAlertDialog()
            }
        }
    }

    class DisableCertValidationAlertDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val dlg = AlertDialog.Builder(activity)
                .setTitle(R.string.settings_disable_cert_validation_title)
                .setMessage(R.string.settings_disable_cert_validation_message)
                .setPositiveButton(R.string.button_enable, null)
                .setNegativeButton(R.string.button_disable) { _, _ ->
                    (activity as SettingsActivity).onDisableCertValidationFromDialog()
                }
                .create()

            dlg.setCancelable(false)
            return dlg
        }

        companion object {
            val TAG = "disable_cert_verify_dialog"

            fun newInstance(): DisableCertValidationAlertDialog {
                return DisableCertValidationAlertDialog()
            }
        }
    }

    class InvalidConnectionDialog: DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val dlg = AlertDialog.Builder(activity)
                .setTitle(R.string.settings_invalid_connection_title)
                .setMessage(arguments.getInt(EXTRA_MESSAGE_ID))
                .setNegativeButton(R.string.button_ok, null)
                .create()

            dlg.setCancelable(false)
            return dlg
        }

        companion object {
            val TAG = "invalid_connection_dialog"
            private val EXTRA_MESSAGE_ID = "extra_message_id"
            fun newInstance(messageId: Int = R.string.settings_invalid_connection_message): InvalidConnectionDialog {
                val args = Bundle()
                args.putInt(EXTRA_MESSAGE_ID, messageId)
                val result = InvalidConnectionDialog()
                result.arguments = args
                return result
            }
        }
    }

    class ConfirmOverwiteDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val dlg = AlertDialog.Builder(activity)
                .setTitle(R.string.settings_confirm_overwrite_title)
                .setMessage(R.string.settings_confirm_overwrite_message)
                .setNegativeButton(R.string.button_no, null)
                .setPositiveButton(R.string.button_yes) { _, _ ->
                    val connection = arguments.getParcelable<Connection>(EXTRA_CONNECTION)
                    val saveAs = SaveAsTask(connection, true)
                    (activity as SettingsActivity).runner.run(SaveAsTask.nameFor(connection), saveAs)
                }
                .create()

            dlg.setCancelable(false)
            return dlg
        }

        companion object {
            val TAG = "confirm_overwrite_dialog"
            private val EXTRA_CONNECTION = "extra_connection"

            fun newInstance(connection: Connection): ConfirmOverwiteDialog {
                val args = Bundle()
                args.putParcelable(EXTRA_CONNECTION, connection)
                val result = ConfirmOverwiteDialog()
                result.arguments = args
                return result
            }
        }
    }

    class SaveAsDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.dialog_edit, null)
            val edit = view.findViewById<EditText>(R.id.edit)

            val dlg = AlertDialog.Builder(activity)
                .setTitle(R.string.settings_save_as_title)
                .setNegativeButton(R.string.button_cancel, null)
                .setPositiveButton(R.string.button_save) { _, _ ->
                    (activity as SettingsActivity).saveAs(edit.text.toString())
                }
                .create()

            dlg.setView(view)
            dlg.setCancelable(false)

            return dlg
        }

        override fun onResume() {
            super.onResume()
            showKeyboard()
        }

        override fun onPause() {
            super.onPause()
            hideKeyboard()
        }

        companion object {
            val TAG = "save_as_dialog"

            fun newInstance(): SaveAsDialog {
                return SaveAsDialog()
            }
        }
    }

    companion object {
        val CONNECTIONS_REQUEST_CODE = 1000

        fun getStartIntent(context: Context): Intent {
            return Intent(context, SettingsActivity::class.java)
        }
    }
}

private class SaveAsTask(val connection: Connection,
                         val overwrite: Boolean = false)
    : Tasks.Blocking<SaveAsTask.Result, Exception>()
{
    enum class Result { Exists, Added }

    override fun exec(context: Context?): Result {
        val dao = Application.connectionsDb?.connectionsDao()!!

        if (!overwrite) {
            val existing: Connection? = dao.query(connection.name)
            if (existing != null) {
                return Result.Exists
            }
        }

        dao.insert(connection)
        return Result.Added
    }

    companion object {
        val NAME = "SaveAsTask"

        fun nameFor(connection: Connection): String {
            return "$NAME.${connection.name}"
        }

        fun match(name: String?): Boolean {
            return name != null && name.startsWith("$NAME.")
        }
    }
}
