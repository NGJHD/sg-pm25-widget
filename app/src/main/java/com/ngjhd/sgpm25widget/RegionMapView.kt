package com.ngjhd.sgpm25widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos

/**
 * "All regions" on the app screen: a plain map of Singapore with one callout
 * per region at the feed's label location (`Pm25.DEFAULT_REGIONS`, which
 * match `regionMetadata`), coloured by its band.
 *
 * The outline is geoBoundaries' SGP ADM0 (simplified, CC BY 4.0), cut down
 * further to ~330 points: the main island plus the five largest islands.
 */
class RegionMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var values: Map<String, Int> = emptyMap()

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private val sea = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDDE9F5.toInt() }
    private val land = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val coast = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.stroke_strong)
    }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17 * sp
        typeface = Typeface.DEFAULT_BOLD
    }
    private val nameText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 9.5f * sp
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
    }

    private val outline = Path()
    private val rect = RectF()

    fun set(values: Map<String, Int>) {
        this.values = values
        contentDescription = "Regions: " + ORDER.joinToString(", ") { name ->
            val v = values[name]
            "${Pm25.displayName(name)} ${v ?: "no reading"}${if (v != null) " " + Pm25.band(v).label else ""}"
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val inner = w - paddingLeft - paddingRight
        val h = (inner * (MAX_LAT - MIN_LAT) / ((MAX_LON - MIN_LON) * K)).toInt()
        setMeasuredDimension(w, h + paddingTop + paddingBottom)
    }

    private fun x(lon: Double): Float =
        (paddingLeft + (lon - MIN_LON) / (MAX_LON - MIN_LON) * (width - paddingLeft - paddingRight)).toFloat()

    private fun y(lat: Double): Float =
        (paddingTop + (MAX_LAT - lat) / (MAX_LAT - MIN_LAT) * (height - paddingTop - paddingBottom)).toFloat()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        outline.reset()
        for (island in OUTLINE) {
            outline.moveTo(x(island[0].toDouble()), y(island[1].toDouble()))
            for (i in 2 until island.size step 2) outline.lineTo(x(island[i].toDouble()), y(island[i + 1].toDouble()))
            outline.close()
        }
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(paddingLeft.toFloat(), paddingTop.toFloat(), (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        canvas.drawRoundRect(rect, 10 * dp, 10 * dp, sea)
        canvas.save()
        canvas.clipRect(rect)
        canvas.drawPath(outline, land)
        canvas.drawPath(outline, coast)
        canvas.restore()

        val bw = 60 * dp
        val bh = 40 * dp
        val gap = 6 * dp
        val centres = Pm25.DEFAULT_REGIONS.associate { it.name to (x(it.lon) to y(it.lat)) }
        val mid = centres["central"]?.second
        for (name in ORDER) {
            var (cx, cy) = centres[name] ?: continue
            // The label points are only ~6.8 km apart north-south: on a narrow
            // screen the north and south boxes would touch central's.
            if (mid != null && name == "north") cy = minOf(cy, mid - bh - gap)
            if (mid != null && name == "south") cy = maxOf(cy, mid + bh + gap)
            val v = values[name]
            val band = v?.let { Pm25.band(it) }
            box.color = band?.color ?: context.getColor(R.color.text_muted)
            val fg = band?.text ?: 0xFFFFFFFF.toInt()
            valueText.color = fg
            nameText.color = fg
            rect.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
            canvas.drawRoundRect(rect, 6 * dp, 6 * dp, box)
            canvas.drawText(v?.toString() ?: "–", cx, cy + 2 * dp, valueText)
            canvas.drawText(name.uppercase(), cx, cy + 15 * dp, nameText)
        }
    }

    private companion object {
        val ORDER = listOf("north", "west", "central", "east", "south")

        const val MIN_LON = 103.59
        const val MAX_LON = 104.10
        const val MIN_LAT = 1.19
        const val MAX_LAT = 1.48
        val K = cos(Math.toRadians(1.335))

        /** lon, lat pairs, one array per island. */
        val OUTLINE = arrayOf(
        floatArrayOf(
            103.8183f, 1.4709f, 103.8054f, 1.4697f, 103.8076f, 1.4657f, 103.8031f, 1.4690f,
            103.7771f, 1.4512f, 103.7709f, 1.4486f, 103.7692f, 1.4519f, 103.7627f, 1.4378f,
            103.7366f, 1.4387f, 103.7372f, 1.4427f, 103.7303f, 1.4495f, 103.7193f, 1.4510f,
            103.6744f, 1.4250f, 103.6730f, 1.4140f, 103.6624f, 1.4056f, 103.6615f, 1.3887f,
            103.6383f, 1.3504f, 103.6342f, 1.3491f, 103.6365f, 1.3456f, 103.6181f, 1.2971f,
            103.6200f, 1.2835f, 103.6121f, 1.2766f, 103.6060f, 1.2626f, 103.6138f, 1.2677f,
            103.6140f, 1.2557f, 103.6082f, 1.2521f, 103.6125f, 1.2453f, 103.6053f, 1.2440f,
            103.6162f, 1.2372f, 103.6178f, 1.2272f, 103.6089f, 1.2196f, 103.6065f, 1.2248f,
            103.6065f, 1.2203f, 103.6442f, 1.2115f, 103.6104f, 1.2195f, 103.6190f, 1.2274f,
            103.6175f, 1.2363f, 103.6217f, 1.2383f, 103.6155f, 1.2543f, 103.6291f, 1.2609f,
            103.6217f, 1.2579f, 103.6216f, 1.2618f, 103.6387f, 1.2701f, 103.6390f, 1.2661f,
            103.6324f, 1.2631f, 103.6394f, 1.2658f, 103.6404f, 1.2791f, 103.6500f, 1.2825f,
            103.6352f, 1.3109f, 103.6376f, 1.3164f, 103.6433f, 1.3113f, 103.6518f, 1.3157f,
            103.6462f, 1.3090f, 103.6490f, 1.3038f, 103.6570f, 1.3111f, 103.6498f, 1.3017f,
            103.6534f, 1.3026f, 103.6518f, 1.2993f, 103.6573f, 1.2979f, 103.6549f, 1.2956f,
            103.6582f, 1.2972f, 103.6648f, 1.2919f, 103.6645f, 1.2869f, 103.6744f, 1.2953f,
            103.6687f, 1.3063f, 103.6727f, 1.3068f, 103.6784f, 1.2966f, 103.6778f, 1.2997f,
            103.6819f, 1.2980f, 103.6814f, 1.3100f, 103.6839f, 1.2991f, 103.6938f, 1.3006f,
            103.6951f, 1.3078f, 103.7024f, 1.3018f, 103.7087f, 1.3020f, 103.7108f, 1.3084f,
            103.7113f, 1.2972f, 103.7219f, 1.2995f, 103.7127f, 1.3089f, 103.7279f, 1.3001f,
            103.7432f, 1.3011f, 103.7492f, 1.2963f, 103.7577f, 1.3001f, 103.7630f, 1.2946f,
            103.7607f, 1.2906f, 103.7723f, 1.2837f, 103.7691f, 1.2784f, 103.7565f, 1.2858f,
            103.7502f, 1.2805f, 103.7707f, 1.2682f, 103.7782f, 1.2809f, 103.7806f, 1.2795f,
            103.7871f, 1.2738f, 103.7786f, 1.2592f, 103.7855f, 1.2550f, 103.7953f, 1.2716f,
            103.8051f, 1.2623f, 103.8067f, 1.2662f, 103.8161f, 1.2638f, 103.8176f, 1.2667f,
            103.8199f, 1.2619f, 103.8397f, 1.2676f, 103.8512f, 1.2607f, 103.8516f, 1.2728f,
            103.8608f, 1.2647f, 103.8715f, 1.2806f, 103.8777f, 1.2807f, 103.8937f, 1.2935f,
            103.9631f, 1.3146f, 103.9835f, 1.3168f, 103.9839f, 1.3121f, 103.9907f, 1.3140f,
            104.0006f, 1.3103f, 104.0224f, 1.3154f, 104.0229f, 1.3200f, 104.0327f, 1.3161f,
            104.0323f, 1.3249f, 104.0210f, 1.3270f, 104.0221f, 1.3346f, 104.0327f, 1.3508f,
            104.0327f, 1.3584f, 104.0420f, 1.3520f, 104.0433f, 1.3541f, 104.0137f, 1.3702f,
            104.0063f, 1.3700f, 104.0088f, 1.3736f, 103.9990f, 1.3890f, 103.9817f, 1.3930f,
            103.9738f, 1.3904f, 103.9766f, 1.3863f, 103.9716f, 1.3868f, 103.9668f, 1.3802f,
            103.9490f, 1.3831f, 103.9378f, 1.3900f, 103.9302f, 1.3995f, 103.9308f, 1.4040f,
            103.9125f, 1.4212f, 103.9032f, 1.4199f, 103.8980f, 1.4147f, 103.8969f, 1.4206f,
            103.8889f, 1.4256f, 103.8866f, 1.4236f, 103.8882f, 1.4258f, 103.8771f, 1.4320f,
            103.8697f, 1.4286f, 103.8696f, 1.4246f, 103.8574f, 1.4254f, 103.8626f, 1.4350f,
            103.8548f, 1.4483f, 103.8457f, 1.4479f, 103.8459f, 1.4541f, 103.8480f, 1.4491f,
            103.8521f, 1.4513f, 103.8505f, 1.4555f, 103.8364f, 1.4641f, 103.8329f, 1.4599f,
            103.8343f, 1.4647f, 103.8276f, 1.4686f, 103.8246f, 1.4633f, 103.8221f, 1.4666f,
            103.8185f, 1.4639f, 103.8224f, 1.4690f,
        ),
        floatArrayOf(
            104.0564f, 1.4320f, 104.0523f, 1.4280f, 104.0428f, 1.4314f, 104.0375f, 1.4250f,
            104.0288f, 1.4252f, 104.0306f, 1.4126f, 104.0163f, 1.4292f, 104.0097f, 1.4242f,
            104.0092f, 1.4187f, 104.0140f, 1.4091f, 104.0303f, 1.4115f, 104.0288f, 1.4029f,
            104.0155f, 1.4016f, 104.0236f, 1.3864f, 104.0163f, 1.4007f, 104.0196f, 1.3984f,
            104.0355f, 1.4056f, 104.0381f, 1.3957f, 104.0534f, 1.3759f, 104.0789f, 1.3868f,
            104.0835f, 1.4064f, 104.0871f, 1.4055f, 104.0877f, 1.3972f, 104.0743f, 1.3633f,
            104.0836f, 1.3388f, 104.0721f, 1.3455f, 104.0827f, 1.3376f, 104.0746f, 1.3615f,
            104.0883f, 1.4039f, 104.0837f, 1.4070f, 104.0856f, 1.4100f, 104.0805f, 1.4192f,
        ),
        floatArrayOf(
            103.7123f, 1.2919f, 103.7113f, 1.2966f, 103.7113f, 1.2917f, 103.7035f, 1.2903f,
            103.7110f, 1.2779f, 103.7084f, 1.2754f, 103.7002f, 1.2849f, 103.6885f, 1.2723f,
            103.6841f, 1.2764f, 103.6957f, 1.2902f, 103.6878f, 1.2906f, 103.6564f, 1.2710f,
            103.6692f, 1.2453f, 103.6651f, 1.2426f, 103.6761f, 1.2279f, 103.6719f, 1.2236f,
            103.6696f, 1.2284f, 103.6725f, 1.2227f, 103.6903f, 1.2401f, 103.6859f, 1.2577f,
            103.6802f, 1.2630f, 103.6850f, 1.2684f, 103.6936f, 1.2605f, 103.6962f, 1.2510f,
            103.7139f, 1.2588f, 103.7047f, 1.2654f, 103.7066f, 1.2688f, 103.7221f, 1.2609f,
            103.7290f, 1.2651f, 103.7232f, 1.2688f, 103.7263f, 1.2716f, 103.7328f, 1.2692f,
            103.7402f, 1.2730f, 103.7247f, 1.2894f,
        ),
        floatArrayOf(
            103.9348f, 1.4275f, 103.9265f, 1.4239f, 103.9284f, 1.4191f, 103.9486f, 1.4098f,
            103.9582f, 1.3994f, 103.9707f, 1.4020f, 103.9785f, 1.4080f, 103.9924f, 1.4083f,
            103.9897f, 1.4181f, 103.9590f, 1.4166f,
        ),
        floatArrayOf(
            103.8092f, 1.2600f, 103.8065f, 1.2595f, 103.8096f, 1.2558f, 103.8329f, 1.2378f,
            103.8398f, 1.2405f, 103.8407f, 1.2476f, 103.8416f, 1.2423f, 103.8482f, 1.2512f,
            103.8410f, 1.2500f, 103.8416f, 1.2537f,
        ),
        floatArrayOf(
            103.7620f, 1.2112f, 103.7587f, 1.2178f, 103.7598f, 1.1959f, 103.7641f, 1.1914f,
            103.7603f, 1.1965f, 103.7662f, 1.2024f, 103.7750f, 1.1969f, 103.7712f, 1.1905f,
            103.7650f, 1.1906f, 103.7702f, 1.1894f, 103.7809f, 1.2003f, 103.7812f, 1.2094f,
        ),
        )
    }
}
