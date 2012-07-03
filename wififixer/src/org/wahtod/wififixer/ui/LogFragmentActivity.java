/*Copyright [2010-2012] [David Van de Ven]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.wahtod.wififixer.ui;

import org.wahtod.wififixer.R;
import org.wahtod.wififixer.legacy.ActionBarDetector;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.view.MenuItem;

public class LogFragmentActivity extends AppFragmentActivity {
	@Override
	public void onCreate(Bundle savedInstanceState) {
		setContentView(R.layout.fragment_container);
		super.onCreate(savedInstanceState);
		if (savedInstanceState == null) {
			FragmentTransaction ft = getSupportFragmentManager()
					.beginTransaction();
			Fragment f = LogFragment.newInstance(savedInstanceState);
			f.setRetainInstance(true);
			ft.add(R.id.fragment_target, f, null);
			ft.commit();
		}
		ActionBarDetector.setDisplayHomeAsUpEnabled(this, true);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		ActionBarDetector.handleHome(this, item);
		return super.onOptionsItemSelected(item);
	}
}
