/*
 * Wifi Fixer for Android
 *        Copyright (C) 2010-2016  David Van de Ven
 *
 *        This program is free software: you can redistribute it and/or modify
 *        it under the terms of the GNU General Public License as published by
 *        the Free Software Foundation, either version 3 of the License, or
 *        (at your option) any later version.
 *
 *        This program is distributed in the hope that it will be useful,
 *        but WITHOUT ANY WARRANTY; without even the implied warranty of
 *        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *        GNU General Public License for more details.
 *
 *        You should have received a copy of the GNU General Public License
 *        along with this program.  If not, see http://www.gnu.org/licenses
 */

package org.wahtod.wififixer.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

import org.wahtod.wififixer.utility.StatusDispatcher;

public class FixerWidgetSmall extends AppWidgetProvider {
    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        WidgetHelper.findAppWidgets(context);
    }

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        WidgetHelper.findAppWidgets(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent.getAction().equals(
                StatusDispatcher.ACTION_WIDGET_NOTIFICATION))
            doStatusUpdate(context, intent);
    }

    private void doStatusUpdate(Context context, Intent intent) {
        Intent start = UpdateService.updateIntent(context,
                StatusUpdateService.class, FixerWidgetSmall.class.getName());
        start.fillIn(intent, Intent.FILL_IN_DATA);
        context.startService(start);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager,
                         int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);
        /*
         * Send Update To Widgets
		 */
        context.startService(UpdateService.updateIntent(context,
                UpdateService.class, FixerWidgetSmall.class.getName()));
    }
}
