package to.augmented.reality.android.common.sensor.orientation;

import java.util.*;

/*
 * Copyright 2013, Kaleb Kircher - Boki Software, Kircher Electronics
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Implements a mean filter designed to smooth the data points based on a mean.
 *
 * @author Kaleb
 * @version %I%, %G%
 *
 */
@SuppressWarnings("JavadocReference")
public class MeanFilter
{
	// The size of the mean filters rolling window.
	private int filterWindow = 30;

	private boolean dataInit;

	private ArrayList<LinkedList<Number>> dataLists;

	/**
	 * Initialize a new MeanFilter object.
	 */
	public MeanFilter()
	{
		dataLists = new ArrayList<LinkedList<Number>>();
		dataInit = false;
	}

	/**
	 * Filter the data.
	 *
	 * @param iterator
	 *            contains input the data.
	 * @return the filtered output data.
	 */
	public float[] filterFloat(float[] data)
	{
		for (int i = 0; i < data.length; i++)
		{
			// Initialize the data structures for the data set.
			if (!dataInit)
			{
				dataLists.add(new LinkedList<Number>());
			}

			dataLists.get(i).addLast(data[i]);

			if (dataLists.get(i).size() > filterWindow)
			{
				dataLists.get(i).removeFirst();
			}
		}

		dataInit = true;

		float[] means = new float[dataLists.size()];

		for (int i = 0; i < dataLists.size(); i++)
		{
			means[i] = (float) getMean(dataLists.get(i));
		}

		return means;
	}

	/**
	 * Get the mean of the data set.
	 *
	 * @param data
	 *            the data set.
	 * @return the mean of the data set.
	 */
	private float getMean(List<Number> data)
	{
		float m = 0;
		float count = 0;

		for (int i = 0; i < data.size(); i++)
		{
			m += data.get(i).floatValue();
			count++;
		}

		if (count != 0)
		{
			m = m / count;
		}

		return m;
	}

	public void setWindowSize(int size)
	{
		this.filterWindow = size;
	}
}
