package dev.horizon.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Base64
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

private data class MojangSkinAsset(val bitmap: Bitmap, val slim: Boolean)

@Composable
internal fun AccountSkinPreview(uuid: String, playerName: String) {
    val context = LocalContext.current
    var reload by remember(uuid) { mutableIntStateOf(0) }
    var asset by remember(uuid) { mutableStateOf<MojangSkinAsset?>(null) }
    var error by remember(uuid) { mutableStateOf<String?>(null) }
    var loading by remember(uuid) { mutableStateOf(true) }

    LaunchedEffect(uuid, reload) {
        loading = true
        error = null
        asset = null
        runCatching { loadMojangSkin(context, uuid) }
            .onSuccess { asset = it }
            .onFailure { error = it.message ?: "Could not load this Minecraft skin." }
        loading = false
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        color = androidx.compose.ui.graphics.Color(0xFFE8ECFF),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("3D SKIN PREVIEW", fontSize = 10.sp, fontWeight = FontWeight.Black, color = androidx.compose.ui.graphics.Color(0xFF27378F))
            Text(
                playerName,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color(0xFF171B2A)
            )
            when {
                asset != null -> {
                    val modelLabel = if (asset!!.slim) "Slim" else "Classic"
                    val preview = remember(uuid) { SkinPreviewSurface(context) }
                    DisposableEffect(preview) {
                        preview.onResume()
                        onDispose { preview.onPause() }
                    }
                    AndroidView(
                        factory = { preview },
                        modifier = Modifier.fillMaxWidth().height(250.dp),
                        update = { it.setSkin(asset!!.bitmap, asset!!.slim) }
                    )
                    Text(
                        "Drag to rotate · $modelLabel model",
                        color = androidx.compose.ui.graphics.Color(0xFF596681),
                        fontSize = 10.sp
                    )
                }
                loading -> Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    Text(
                        error ?: "Skin is not available for this account.",
                        color = androidx.compose.ui.graphics.Color(0xFF596681),
                        fontSize = 11.sp
                    )
                    androidx.compose.material3.TextButton(onClick = { reload++ }) {
                        Text("Retry skin download")
                    }
                }
            }
        }
    }
}

private suspend fun loadMojangSkin(context: Context, uuid: String): MojangSkinAsset =
    withContext(Dispatchers.IO) {
        val normalizedUuid = uuid.replace("-", "").trim()
        require(normalizedUuid.matches(Regex("(?i)[0-9a-f]{32}"))) { "This account does not have a valid Java profile UUID." }
        val cacheDir = File(context.cacheDir, "mojang-skins").apply { mkdirs() }
        val imageFile = File(cacheDir, "$normalizedUuid.png")
        val modelFile = File(cacheDir, "$normalizedUuid.model")

        if (imageFile.isFile && imageFile.length() > 0L) {
            val cached = BitmapFactory.decodeFile(imageFile.absolutePath)
            if (cached != null) {
                val slim = runCatching { modelFile.readText() }.getOrDefault("classic") == "slim"
                return@withContext MojangSkinAsset(cached, slim)
            }
        }

        val profileJson = JSONObject(readHttpText("https://sessionserver.mojang.com/session/minecraft/profile/$normalizedUuid"))
        val properties = profileJson.optJSONArray("properties")
            ?: error("The Minecraft profile has no public texture data.")
        var encoded: String? = null
        for (i in 0 until properties.length()) {
            val property = properties.optJSONObject(i) ?: continue
            if (property.optString("name") == "textures") {
                encoded = property.optString("value").takeIf { it.isNotBlank() }
                break
            }
        }
        val decoded = encoded?.let { Base64.decode(it, Base64.DEFAULT) }
            ?: error("The Minecraft profile did not publish a skin texture.")
        val textureJson = JSONObject(String(decoded, Charsets.UTF_8))
        val skinInfo = textureJson.optJSONObject("textures")?.optJSONObject("SKIN")
            ?: error("This Minecraft account has no public skin.")
        val skinUrl = skinInfo.optString("url").takeIf { it.startsWith("https://") }
            ?: error("The skin texture URL is invalid.")
        val model = skinInfo.optJSONObject("metadata")?.optString("model").orEmpty()
        val bytes = readHttpBytes(skinUrl)
        imageFile.writeBytes(bytes)
        val slim = model.equals("slim", ignoreCase = true)
        modelFile.writeText(if (slim) "slim" else "classic")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("The Minecraft skin image could not be decoded.")
        MojangSkinAsset(bitmap, slim)
    }

private fun readHttpText(address: String): String = String(readHttpBytes(address), Charsets.UTF_8)

private fun readHttpBytes(address: String): ByteArray {
    val connection = (URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 10000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Horizon-Android/1.0")
    }
    return try {
        val status = connection.responseCode
        if (status !in 200..299) error("Minecraft skin service returned HTTP $status.")
        connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}

private data class SkinFace(val x: Int, val y: Int, val width: Int, val height: Int)
private data class SkinUv(
    val front: SkinFace,
    val back: SkinFace,
    val left: SkinFace,
    val right: SkinFace,
    val top: SkinFace,
    val bottom: SkinFace
)

private class SkinPreviewSurface(context: Context) : GLSurfaceView(context) {
    private val skinRenderer = SkinModelRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(skinRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        setZOrderOnTop(false)
        setBackgroundColor(android.graphics.Color.rgb(232, 236, 255))
    }

    fun setSkin(bitmap: Bitmap, slim: Boolean) {
        skinRenderer.setSkin(bitmap, slim)
        requestRender()
    }

    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                skinRenderer.rotate(dx, dy)
                requestRender()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
}

private class SkinModelRenderer : GLSurfaceView.Renderer {
    @Volatile private var pendingBitmap: Bitmap? = null
    @Volatile private var slimArms = false
    @Volatile private var yaw = -22f
    @Volatile private var pitch = 8f
    @Volatile private var meshesDirty = true
    private var uploadedBitmap: Bitmap? = null
    private var textureId = 0
    private var program = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var matrixLocation = -1
    private var samplerLocation = -1
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)
    private var meshes: List<FloatArray> = emptyList()

    fun setSkin(bitmap: Bitmap, slim: Boolean) {
        pendingBitmap = bitmap
        if (slimArms != slim) {
            slimArms = slim
            meshesDirty = true
        }
    }

    fun rotate(dx: Float, dy: Float) {
        yaw = (yaw + dx * 0.65f) % 360f
        pitch = (pitch + dy * 0.45f).coerceIn(-28f, 28f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.91f, 0.93f, 1f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        matrixLocation = GLES20.glGetUniformLocation(program, "uMvp")
        samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
        meshes = createPlayerMeshes(slimArms)
        meshesDirty = false
        textureId = 0
        uploadedBitmap = null
        uploadPendingTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, max(1, height))
        Matrix.perspectiveM(projection, 0, 36f, width.toFloat() / max(1, height), 0.1f, 100f)
        Matrix.setLookAtM(view, 0, 0f, 1.8f, 8.2f, 0f, 1.8f, 0f, 0f, 1f, 0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        uploadPendingTexture()
        if (textureId == 0 || program == 0) return

        if (meshesDirty) {
            meshes = createPlayerMeshes(slimArms)
            meshesDirty = false
        }
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, -16f, 0f)
        Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        Matrix.translateM(model, 0, 0f, 16f, 0f)
        Matrix.scaleM(model, 0, 0.12f, 0.12f, 0.12f)
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glEnableVertexAttribArray(texCoordLocation)

        for (mesh in meshes) {
            val buffer: FloatBuffer = ByteBuffer.allocateDirect(mesh.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(mesh)
                    position(0)
                }
            GLES20.glVertexAttribPointer(positionLocation, 3, GLES20.GL_FLOAT, false, 20, buffer)
            buffer.position(3)
            GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.size / 5)
        }
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
    }

    private fun uploadPendingTexture() {
        val bitmap = pendingBitmap ?: return
        if (bitmap === uploadedBitmap && textureId != 0) return
        if (textureId == 0) {
            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0)
            textureId = names[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        uploadedBitmap = bitmap
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, vertex)
            GLES20.glAttachShader(p, fragment)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) error("Could not link the Minecraft skin preview shader: " + GLES20.glGetProgramInfoLog(p))
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) error("Could not compile the Minecraft skin preview shader: " + GLES20.glGetShaderInfoLog(shader))
        }

    companion object {
        private const val VERTEX_SHADER = "attribute vec3 aPosition; attribute vec2 aTexCoord; uniform mat4 uMvp; varying vec2 vTex; void main(){ gl_Position=uMvp*vec4(aPosition,1.0); vTex=aTexCoord; }"
        private const val FRAGMENT_SHADER = "precision mediump float; uniform sampler2D uTexture; varying vec2 vTex; void main(){ vec4 c=texture2D(uTexture,vTex); if(c.a<0.06) discard; gl_FragColor=c; }"

        private fun faceVertices(target: MutableList<Float>, points: Array<FloatArray>, uv: SkinFace) {
            val u0 = uv.x / 64f
            val u1 = (uv.x + uv.width) / 64f
            val vTop = 1f - uv.y / 64f
            val vBottom = 1f - (uv.y + uv.height) / 64f
            val tex = arrayOf(
                floatArrayOf(u0, vBottom),
                floatArrayOf(u1, vBottom),
                floatArrayOf(u1, vTop),
                floatArrayOf(u0, vTop)
            )
            val order = intArrayOf(0, 1, 2, 0, 2, 3)
            for (index in order) {
                target.add(points[index][0])
                target.add(points[index][1])
                target.add(points[index][2])
                target.add(tex[index][0])
                target.add(tex[index][1])
            }
        }

        private fun createCube(
            x: Float, y: Float, z: Float, width: Float, height: Float, depth: Float,
            uv: SkinUv
        ): FloatArray {
            val x0 = x; val x1 = x + width
            val y0 = y; val y1 = y + height
            val z0 = z; val z1 = z + depth
            val out = ArrayList<Float>(180)
            faceVertices(out, arrayOf(
                floatArrayOf(x0,y0,z1), floatArrayOf(x1,y0,z1),
                floatArrayOf(x1,y1,z1), floatArrayOf(x0,y1,z1)
            ), uv.front)
            faceVertices(out, arrayOf(
                floatArrayOf(x1,y0,z0), floatArrayOf(x0,y0,z0),
                floatArrayOf(x0,y1,z0), floatArrayOf(x1,y1,z0)
            ), uv.back)
            faceVertices(out, arrayOf(
                floatArrayOf(x0,y0,z0), floatArrayOf(x0,y0,z1),
                floatArrayOf(x0,y1,z1), floatArrayOf(x0,y1,z0)
            ), uv.left)
            faceVertices(out, arrayOf(
                floatArrayOf(x1,y0,z1), floatArrayOf(x1,y0,z0),
                floatArrayOf(x1,y1,z0), floatArrayOf(x1,y1,z1)
            ), uv.right)
            faceVertices(out, arrayOf(
                floatArrayOf(x0,y1,z1), floatArrayOf(x1,y1,z1),
                floatArrayOf(x1,y1,z0), floatArrayOf(x0,y1,z0)
            ), uv.top)
            faceVertices(out, arrayOf(
                floatArrayOf(x0,y0,z0), floatArrayOf(x1,y0,z0),
                floatArrayOf(x1,y0,z1), floatArrayOf(x0,y0,z1)
            ), uv.bottom)
            return out.toFloatArray()
        }

        private fun createPlayerMeshes(slim: Boolean): List<FloatArray> {
            val arm = if (slim) 3f else 4f
            val rightArmX = -4f - arm
            val leftArmX = 4f
            val rightArmUv = if (slim) {
                SkinUv(SkinFace(44,20,3,12), SkinFace(52,20,3,12), SkinFace(40,20,4,12), SkinFace(47,20,4,12), SkinFace(44,16,3,4), SkinFace(47,16,3,4))
            } else {
                SkinUv(SkinFace(44,20,4,12), SkinFace(52,20,4,12), SkinFace(40,20,4,12), SkinFace(48,20,4,12), SkinFace(44,16,4,4), SkinFace(48,16,4,4))
            }
            val leftArmUv = if (slim) {
                SkinUv(SkinFace(36,52,3,12), SkinFace(44,52,3,12), SkinFace(32,52,4,12), SkinFace(39,52,4,12), SkinFace(36,48,3,4), SkinFace(39,48,3,4))
            } else {
                SkinUv(SkinFace(36,52,4,12), SkinFace(44,52,4,12), SkinFace(32,52,4,12), SkinFace(40,52,4,12), SkinFace(36,48,4,4), SkinFace(40,48,4,4))
            }
            val head = SkinUv(SkinFace(8,8,8,8), SkinFace(24,8,8,8), SkinFace(0,8,8,8), SkinFace(16,8,8,8), SkinFace(8,0,8,8), SkinFace(16,0,8,8))
            val headOverlay = SkinUv(SkinFace(40,8,8,8), SkinFace(56,8,8,8), SkinFace(32,8,8,8), SkinFace(48,8,8,8), SkinFace(40,0,8,8), SkinFace(48,0,8,8))
            val torso = SkinUv(SkinFace(20,20,8,12), SkinFace(32,20,8,12), SkinFace(16,20,4,12), SkinFace(28,20,4,12), SkinFace(20,16,8,4), SkinFace(28,16,8,4))
            val jacket = SkinUv(SkinFace(20,36,8,12), SkinFace(32,36,8,12), SkinFace(16,36,4,12), SkinFace(28,36,4,12), SkinFace(20,32,8,4), SkinFace(28,32,8,4))
            val rightSleeve = if (slim) {
                SkinUv(SkinFace(44,36,3,12), SkinFace(52,36,3,12), SkinFace(40,36,4,12), SkinFace(47,36,4,12), SkinFace(44,32,3,4), SkinFace(47,32,3,4))
            } else {
                SkinUv(SkinFace(44,36,4,12), SkinFace(52,36,4,12), SkinFace(40,36,4,12), SkinFace(48,36,4,12), SkinFace(44,32,4,4), SkinFace(48,32,4,4))
            }
            val leftSleeve = if (slim) {
                SkinUv(SkinFace(52,52,3,12), SkinFace(60,52,3,12), SkinFace(48,52,4,12), SkinFace(55,52,4,12), SkinFace(52,48,3,4), SkinFace(55,48,3,4))
            } else {
                SkinUv(SkinFace(52,52,4,12), SkinFace(60,52,4,12), SkinFace(48,52,4,12), SkinFace(56,52,4,12), SkinFace(52,48,4,4), SkinFace(56,48,4,4))
            }
            val rightLeg = SkinUv(SkinFace(4,20,4,12), SkinFace(12,20,4,12), SkinFace(0,20,4,12), SkinFace(8,20,4,12), SkinFace(4,16,4,4), SkinFace(8,16,4,4))
            val leftLeg = SkinUv(SkinFace(20,52,4,12), SkinFace(28,52,4,12), SkinFace(16,52,4,12), SkinFace(24,52,4,12), SkinFace(20,48,4,4), SkinFace(24,48,4,4))
            val rightPants = SkinUv(SkinFace(4,36,4,12), SkinFace(12,36,4,12), SkinFace(0,36,4,12), SkinFace(8,36,4,12), SkinFace(4,32,4,4), SkinFace(8,32,4,4))
            val leftPants = SkinUv(SkinFace(4,52,4,12), SkinFace(12,52,4,12), SkinFace(0,52,4,12), SkinFace(8,52,4,12), SkinFace(4,48,4,4), SkinFace(8,48,4,4))
            return listOf(
                createCube(-4f,24f,-4f,8f,8f,8f,head),
                createCube(-4.25f,23.75f,-4.25f,8.5f,8.5f,8.5f,headOverlay),
                createCube(-4f,12f,-2f,8f,12f,4f,torso),
                createCube(-4.12f,11.88f,-2.12f,8.24f,12.24f,4.24f,jacket),
                createCube(rightArmX,12f,-2f,arm,12f,4f,rightArmUv),
                createCube(rightArmX-0.1f,11.9f,-2.1f,arm+0.2f,12.2f,4.2f,rightSleeve),
                createCube(leftArmX,12f,-2f,arm,12f,4f,leftArmUv),
                createCube(leftArmX-0.1f,11.9f,-2.1f,arm+0.2f,12.2f,4.2f,leftSleeve),
                createCube(-4f,0f,-2f,4f,12f,4f,rightLeg),
                createCube(-4.1f,-0.1f,-2.1f,4.2f,12.2f,4.2f,rightPants),
                createCube(0f,0f,-2f,4f,12f,4f,leftLeg),
                createCube(-0.1f,-0.1f,-2.1f,4.2f,12.2f,4.2f,leftPants)
            )
        }
    }
}
