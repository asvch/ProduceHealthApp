package com.example.producehealth

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.producehealth.databinding.ActivityMainBinding
import com.google.firebase.ml.modeldownloader.CustomModel
import com.google.firebase.ml.modeldownloader.CustomModelDownloadConditions
import com.google.firebase.ml.modeldownloader.DownloadType
import com.google.firebase.ml.modeldownloader.FirebaseModelDownloader
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder


class MainActivity : AppCompatActivity() {

//    declare our buttons
    lateinit var selectBtn: Button
    lateinit var predictBtn: Button
    lateinit var resView: TextView
    lateinit var imageView: ImageView
    lateinit var bitmap: Bitmap
    lateinit var bmp: Bitmap
    lateinit var interpreter : Interpreter

    private lateinit var binding: ActivityMainBinding


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        Log.i("MainActivity", "Before buttons initialized.")

        // initialize our buttons
        selectBtn = findViewById(R.id.selectBtn)  // when pressed, we want to open gallery & select image
        predictBtn = findViewById(R.id.predictBtn)
        resView = findViewById(R.id.resView)
        imageView = findViewById(R.id.imageView) // show image in image view when image has been selected

    // image processor for our model - REPLACED with function later on
//        var imageProcessor = ImageProcessor.Builder()
//            .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
//            .build()
        // for models that need GRAYSCALE images:
            // .add(TransformToGrayScaleOp) // but might need to change dependencies in gradle app level
        // normalize pixels from 0-1 if using normalization:
            // .add(NormalizeOp(0.0f, 255.0f))

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val loadImage = registerForActivityResult(
            ActivityResultContracts.GetContent(), ActivityResultCallback { uri: Uri? ->
                // Display selected image in ImageView
                binding.imageView.setImageURI(uri)
                // Load bitmap from URI using ImageDecoder
                try {
                    if (Build.VERSION.SDK_INT < 28) {
                        bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                        bmp = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    } else {
                        val source = ImageDecoder.createSource(contentResolver, uri!!)
                        bitmap = ImageDecoder.decodeBitmap(source)
                        bmp = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            })

        // instructions for when buttons pressed
        binding.selectBtn.setOnClickListener(View.OnClickListener {
            Log.i("MainActivity", "SELECT CLICKED")
            loadImage.launch("image/*")  // * means all images from mobile will come here
        })


        // load model when predict button pressed - remember to upload optimized model tflite file
        binding.predictBtn.setOnClickListener {
            Log.i("MainActivity", "PREDICT CLICKED")

            val input = preprocessBitmap(bmp)
            Log.i("MainActivity", "Got Input")
            initializeInterpreter(input)

        }

//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.*)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }
    }

    private fun preprocessBitmap(bmp: Bitmap): ByteBuffer {

        val scaledBitmap = Bitmap.createScaledBitmap(bmp, 224, 224, true)
        val input = ByteBuffer.allocateDirect(224 * 224 * 3 * 4).order(ByteOrder.nativeOrder())
//        val input = ByteBuffer.allocateDirect(224 * 224 * 3 * 1).order(ByteOrder.nativeOrder())
//        val input = ByteBuffer.allocateDirect(224 * 224 * 3).order(ByteOrder.nativeOrder())
        for (y in 0 until 224) {
            for (x in 0 until 224) {
                val px = scaledBitmap.getPixel(x, y)

                // Get channel values from the pixel value.
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)

                // Normalize channel values to [-1.0, 1.0]. This requirement depends on the model.
//                val rf = (r - 127) / 255f
//                val gf = (g - 127) / 255f
//                val bf = (b - 127) / 255f

//                val rf = r / 255f
//                val gf = g / 255f
//                val bf = b / 255f

                val rf = r / 1f
                val gf = g / 1f
                val bf = b / 1f

                input.putFloat(rf)
                input.putFloat(gf)
                input.putFloat(bf)
//                input.put((r and 0xFF).toByte())
//                input.put((g and 0xFF).toByte())
//                input.put((b and 0xFF).toByte())
            }
        }
        return input
    }

    // Function to initialize the interpreter and download the model
    private fun initializeInterpreter(input: ByteBuffer) {
        val conditions = CustomModelDownloadConditions.Builder()
            .requireWifi()   // Also possible: .requireCharging() and .requireDeviceIdle()
            .build()

        FirebaseModelDownloader.getInstance()
            .getModel("producemodel", DownloadType.LOCAL_MODEL, conditions)  // using firebase to download our custom model
            .addOnSuccessListener { model: CustomModel? ->
                // Download complete. Depending on your app, you could enable the ML
                // feature, or switch from the local model to the remote model, etc.
                Log.i("MainActivity", "Model Success")

                val modelFile = model?.file
                if (modelFile != null) {
                    interpreter = Interpreter(modelFile)
                    // Call the function to run the prediction after the interpreter is initialized
                    runModelPrediction(input)
                    Log.i("MainActivity", "Model File not null")
                }
                else{
                    Log.i("MainActivity", "MODEL NULL")
                }
            }
            .addOnFailureListener {
                Log.i("MainActivity", "MODEL FAILED")
            }
            .addOnCompleteListener {
                Log.i("MainActivity", "Process Complete")
            }
    }

    // Function to run the model prediction
    private fun runModelPrediction(input: ByteBuffer) {
        if (!::interpreter.isInitialized) {
            Log.e("MainActivity", "Interpreter has not been initialized")
            return
        }

        val bufferSize = 28 * java.lang.Float.SIZE / java.lang.Byte.SIZE   // 28 possible classifications for this model
        val modelOutput = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder())
        interpreter.run(input, modelOutput)

        modelOutput.rewind()
        val probabilities = modelOutput.asFloatBuffer()

        var maxProbability = Float.MIN_VALUE
        var maxIdx = -1

        // read labels.txt file with labels of models (uploaded to assets folder)
        // readLineS returns list of strings in file
        var labels = application.assets.open("labels.txt").bufferedReader().readLines()

        for (i in 0 until probabilities.capacity()) {
            val probability = probabilities.get(i)
            println("Probability of class $i: $probability")

            if (probability > maxProbability) {
                maxProbability = probability
                maxIdx = i
            }
            Log.d("ModelOutput", "Selected Class: $maxIdx with Probability: $maxProbability")
        }

        Log.i("MaxIdx", maxIdx.toString())
        Log.i("MaxIdx", labels[maxIdx])

        binding.resView.text = labels[maxIdx]   // outputs result to app's text view

    }

}