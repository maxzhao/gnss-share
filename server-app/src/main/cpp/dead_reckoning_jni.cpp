#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <initializer_list>
#include <mutex>
#include <new>

#include "commons.h"
#include "mlm.h"

namespace {

class FilterSession {
 public:
  FilterSession(double acceleration_variance,
                double location_variance,
                double speed_variance)
      : filter(acceleration_variance, location_variance, speed_variance) {}

  std::mutex mutex;
  MLM filter;
  bool initialized = false;
};

FilterSession* from_handle(jlong handle) {
  return reinterpret_cast<FilterSession*>(static_cast<intptr_t>(handle));
}

jlong to_handle(FilterSession* session) {
  return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

bool finite_all(std::initializer_list<double> values) {
  for (double value : values) {
    if (!std::isfinite(value)) {
      return false;
    }
  }
  return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dezz_gnssshare_server_DeadReckoningNative_nativeCreate(
    JNIEnv*,
    jclass,
    jdouble acceleration_variance,
    jdouble location_variance,
    jdouble speed_variance) {
  if (!finite_all({acceleration_variance, location_variance, speed_variance}) ||
      acceleration_variance <= 0 || location_variance <= 0 || speed_variance <= 0) {
    return 0;
  }
  try {
    return to_handle(new FilterSession(
        acceleration_variance, location_variance, speed_variance));
  } catch (...) {
    return 0;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dezz_gnssshare_server_DeadReckoningNative_nativeCorrect(
    JNIEnv*,
    jclass,
    jlong handle,
    jdouble latitude,
    jdouble longitude,
    jdouble altitude,
    jdouble location_variance,
    jdouble speed,
    jdouble bearing,
    jdouble speed_variance,
    jdouble monotonic_seconds) {
  FilterSession* session = from_handle(handle);
  if (session == nullptr ||
      !finite_all({latitude, longitude, altitude, location_variance, speed,
                   bearing, speed_variance, monotonic_seconds}) ||
      latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 ||
      location_variance <= 0 || speed_variance <= 0) {
    return JNI_FALSE;
  }

  std::lock_guard<std::mutex> lock(session->mutex);
  try {
    if (session->initialized) {
      session->filter.process_acc_data(
          enu_accelerometer(0.0, 0.0, 0.0), monotonic_seconds);
    }
    session->filter.process_gps_data(
        gps_coordinate(latitude, longitude, altitude, location_variance,
                       std::max(0.0, speed), bearing, speed_variance),
        monotonic_seconds);
    session->initialized = true;
    return JNI_TRUE;
  } catch (...) {
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dezz_gnssshare_server_DeadReckoningNative_nativePredict(
    JNIEnv*,
    jclass,
    jlong handle,
    jdouble east_acceleration,
    jdouble north_acceleration,
    jdouble up_acceleration,
    jdouble monotonic_seconds) {
  FilterSession* session = from_handle(handle);
  if (session == nullptr ||
      !finite_all({east_acceleration, north_acceleration, up_acceleration,
                   monotonic_seconds})) {
    return JNI_FALSE;
  }

  std::lock_guard<std::mutex> lock(session->mutex);
  if (!session->initialized) {
    return JNI_FALSE;
  }
  try {
    return session->filter.process_acc_data(
               enu_accelerometer(east_acceleration, north_acceleration,
                                 up_acceleration),
               monotonic_seconds)
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_dezz_gnssshare_server_DeadReckoningNative_nativeEstimate(
    JNIEnv* env,
    jclass,
    jlong handle) {
  FilterSession* session = from_handle(handle);
  if (session == nullptr) {
    return nullptr;
  }

  gps_coordinate estimate;
  {
    std::lock_guard<std::mutex> lock(session->mutex);
    if (!session->initialized) {
      return nullptr;
    }
    try {
      estimate = session->filter.predicted_coordinate();
    } catch (...) {
      return nullptr;
    }
  }

  const double android_bearing_rad =
      cartezian_to_azimuth_rad(degree_to_rad(estimate.speed.azimuth));
  const double android_bearing = rad_to_degree(android_bearing_rad);
  const double values[] = {estimate.location.latitude,
                           estimate.location.longitude,
                           estimate.speed.value,
                           android_bearing};
  if (!finite_all({values[0], values[1], values[2], values[3]}) ||
      values[0] < -90 || values[0] > 90 || values[1] < -180 || values[1] > 180) {
    return nullptr;
  }

  jdoubleArray result = env->NewDoubleArray(4);
  if (result != nullptr) {
    env->SetDoubleArrayRegion(result, 0, 4, values);
  }
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dezz_gnssshare_server_DeadReckoningNative_nativeDestroy(
    JNIEnv*,
    jclass,
    jlong handle) {
  delete from_handle(handle);
}
