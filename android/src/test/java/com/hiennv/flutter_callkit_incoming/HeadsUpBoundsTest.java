package com.hiennv.flutter_callkit_incoming;

import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** RemoteViews host bounds, not a claim of physical MIUI/HyperOS testing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {31, 35})
public class HeadsUpBoundsTest {
    private int dp(Context c, int n) {
        return Math.round(n * c.getResources().getDisplayMetrics().density);
    }

    @Test public void compactVisualsFitConstrainedHostAndActionsRemainBound() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        CallkitNotificationManager manager = new CallkitNotificationManager(c, null);
        Bundle data = new Bundle();
        data.putString(CallkitConstants.EXTRA_CALLKIT_NAME_CALLER, "A very long caller name with Unicode Élodie García Márquez");
        data.putString(CallkitConstants.EXTRA_CALLKIT_ID, "4a66c493-b5d0-4186-a3af-0a05c9600d28");
        Method make = CallkitNotificationManager.class.getDeclaredMethod("createCompactHeadsUpViews", int.class, Bundle.class);
        make.setAccessible(true);
        for (int width : new int[]{240, 280, 360}) {
            for (int height : new int[]{40, 48, 88}) {
                RemoteViews remote = (RemoteViews) make.invoke(manager, 17, data);
                View root = remote.apply(c, new FrameLayout(c));
                root.measure(View.MeasureSpec.makeMeasureSpec(dp(c, width), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dp(c, height), View.MeasureSpec.AT_MOST));
                root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
                assertTrue(root.getMeasuredHeight() <= dp(c, height));

                assertEquals(1, ((TextView) root.findViewById(R.id.tvNameCaller)).getMaxLines());
                for (int id : new int[]{R.id.llAccept, R.id.llDecline}) {
                    ViewGroup action = root.findViewById(id);
                    View circle = action.getChildAt(0);
                    assertTrue(action.isClickable());
                    assertTrue(action.isFocusable());
                    assertNotNull(action.getContentDescription());
                    assertEquals(dp(c, 48), action.getMeasuredWidth());
                    if (height >= 48) assertTrue(action.getMeasuredHeight() >= dp(c, 48));
                    assertTrue(circle.getTop() >= 0);
                    assertTrue(circle.getBottom() <= action.getMeasuredHeight());
                    assertEquals(dp(c, 32), circle.getMeasuredHeight());
                }
                View avatar = root.findViewById(R.id.ivAvatar);
                assertTrue(avatar.getTop() >= 0);
                assertTrue(avatar.getBottom() <= root.getMeasuredHeight());
                assertTrue(avatar.getClipToOutline());
                assertNull(root.findViewById(R.id.tvNumber));
            }
        }
    }

    @Test public void xiaomiSystemFallbackOnlyReplacesHeadsUpSurface() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Build.class, "MANUFACTURER", "Xiaomi");
        ReflectionHelpers.setStaticField(Build.class, "BRAND", "Redmi");
        CallkitNotificationManager manager = new CallkitNotificationManager(c, null);
        Bundle data = new Bundle();
        data.putString(CallkitConstants.EXTRA_CALLKIT_ID, "4a66c493-b5d0-4186-a3af-0a05c9600d28");
        data.putString(CallkitConstants.EXTRA_CALLKIT_NAME_CALLER, "Caller");
        Field field = CallkitNotificationManager.class.getDeclaredField("headsUpData");
        field.setAccessible(true);
        field.set(manager, data);
        Notification notification = new NotificationCompat.Builder(c, "existing-channel")
                .setSmallIcon(R.drawable.ic_accept)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(new RemoteViews(c.getPackageName(), R.layout.layout_custom_small_notification))
                .setCustomBigContentView(new RemoteViews(c.getPackageName(), R.layout.layout_custom_notification))
                .setCustomHeadsUpContentView(new RemoteViews(c.getPackageName(), R.layout.layout_custom_heads_up_notification))
                .build();
        RemoteViews expanded = notification.bigContentView;
        RemoteViews collapsed = notification.contentView;
        RemoteViews headsUp = notification.headsUpContentView;
        int flags = notification.flags;
        Method fallback = CallkitNotificationManager.class.getDeclaredMethod("applySystemHeadsUpFallback", Notification.class);
        fallback.setAccessible(true);
        fallback.invoke(manager, notification);
        assertNotNull(notification.headsUpContentView);
        assertNotSame(headsUp, notification.headsUpContentView);
        assertSame(expanded, notification.bigContentView);
        assertSame(collapsed, notification.contentView);
        assertEquals(flags, notification.flags);
        assertEquals("existing-channel", notification.getChannelId());
    }
}
