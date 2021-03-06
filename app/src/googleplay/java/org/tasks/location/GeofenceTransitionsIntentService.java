package org.tasks.location;

import static com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_ENTER;
import static com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_EXIT;
import static com.todoroo.andlib.utility.DateUtilities.now;
import static com.todoroo.astrid.reminders.ReminderService.TYPE_GEOFENCE_ENTER;
import static com.todoroo.astrid.reminders.ReminderService.TYPE_GEOFENCE_EXIT;
import static org.tasks.time.DateTimeUtils.currentTimeMillis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.JobIntentService;
import com.google.android.gms.location.GeofencingEvent;
import com.google.common.collect.Lists;
import java.util.List;
import javax.inject.Inject;
import org.tasks.Notifier;
import org.tasks.data.Geofence;
import org.tasks.data.LocationDao;
import org.tasks.data.Place;
import org.tasks.injection.InjectingJobIntentService;
import org.tasks.injection.ServiceComponent;
import org.tasks.notifications.Notification;
import timber.log.Timber;

public class GeofenceTransitionsIntentService extends InjectingJobIntentService {

  @Inject LocationDao locationDao;
  @Inject Notifier notifier;

  @Override
  protected void doWork(Intent intent) {
    GeofencingEvent geofencingEvent = GeofencingEvent.fromIntent(intent);
    if (geofencingEvent.hasError()) {
      Timber.e("geofence error code %s", geofencingEvent.getErrorCode());
      return;
    }

    int transitionType = geofencingEvent.getGeofenceTransition();

    List<com.google.android.gms.location.Geofence> triggeringGeofences =
        geofencingEvent.getTriggeringGeofences();
    Timber.i("Received geofence transition: %s, %s", transitionType, triggeringGeofences);
    if (transitionType == GEOFENCE_TRANSITION_ENTER || transitionType == GEOFENCE_TRANSITION_EXIT) {
      for (com.google.android.gms.location.Geofence triggerGeofence : triggeringGeofences) {
        triggerNotification(triggerGeofence, transitionType == GEOFENCE_TRANSITION_ENTER);
      }
    } else {
      Timber.w("invalid geofence transition type: %s", transitionType);
    }
  }

  @Override
  protected void inject(ServiceComponent component) {
    component.inject(this);
  }

  private void triggerNotification(
      com.google.android.gms.location.Geofence triggeringGeofence, boolean arrival) {
    String requestId = triggeringGeofence.getRequestId();
    try {
      Place place = locationDao.getPlace(requestId);
      if (place == null) {
        Timber.e("Can't find place for requestId %s", requestId);
        return;
      }
      List<Geofence> geofences = arrival
          ? locationDao.getArrivalGeofences(place.getUid(), now())
          : locationDao.getDepartureGeofences(place.getUid(), now());
      notifier.triggerNotifications(
          Lists.transform(geofences, g -> toNotification(place, g, arrival)));
    } catch (Exception e) {
      Timber.e(e, "Error triggering geofence %s: %s", requestId, e.getMessage());
    }
  }

  private Notification toNotification(Place place, Geofence geofence, boolean arrival) {
    Notification notification = new Notification();
    notification.setTaskId(geofence.getTask());
    notification.setType(arrival ? TYPE_GEOFENCE_ENTER : TYPE_GEOFENCE_EXIT);
    notification.setTimestamp(currentTimeMillis());
    notification.setLocation(place.getId());
    return notification;
  }

  public static class Broadcast extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      JobIntentService.enqueueWork(
          context,
          GeofenceTransitionsIntentService.class,
          InjectingJobIntentService.JOB_ID_GEOFENCE_TRANSITION,
          intent);
    }
  }
}
