#!/bin/bash
# Downloads the on-device detector: TFLite runtime (Maven Central) and
# EfficientDet-Lite0 (TensorFlow task-library export, which has the
# TFLite_Detection_PostProcess op - the MediaPipe export only gives raw
# anchor outputs). Kept out of git.
set -euo pipefail
cd "$(dirname "$0")/deps"
M=https://repo1.maven.org/maven2/org/tensorflow
curl -fsSLO $M/tensorflow-lite/2.16.1/tensorflow-lite-2.16.1.aar
curl -fsSLO $M/tensorflow-lite-api/2.16.1/tensorflow-lite-api-2.16.1.aar
curl -fsSL -o efficientdet_lite0.tflite https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite
# SSD MobileNet v1 (300x300): ~3x faster on the box's 32-bit CPU, the default
curl -fsSL -o ssd_mobilenet_v1.tflite https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_ssd_mobilenet_v1_1_metadata_2.tflite
shasum -a 256 *
