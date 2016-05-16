package com.android.sensordatareceiver;


import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class DetailsFragment extends Fragment 
{
  /**
   * Create a new instance of DetailsFragment, initialized to
   * show the text at 'index'.
  */
  public static DetailsFragment newInstance(int type,String value,String time)
  {
    DetailsFragment f = new DetailsFragment();
    // Supply index input as an argument.
    Bundle args = new Bundle();
    args.putInt("type",type);
    args.putString("value", value);
    args.putString("time", time);
    f.setArguments(args);
    return f;
  }

  public int getType() 
  {
    return getArguments().getInt("type");
  }

  public String getValue() 
  {
    return getArguments().getString("value");
  }
  
  public String getTime()
  {
    return getArguments().getString("time");
  }
  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,Bundle savedInstanceState)
  {
    View v = null;
    String value = getValue();
    String time = getTime();
    int type = getType();
    if(value == null)
    {
      v =  inflater.inflate(R.layout.no_packets, container, false);
    }
    else
    {
      switch(type)
      {
      case 1 :
    	  v =  inflater.inflate(R.layout.no_packets, container, false);
          TextView text = (TextView) v.findViewById(R.id.textValue);
          text.setText("Temperature : " + value  + "\nReceived at :" + time);
    	  v.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        break;
      case 2:
          v =  inflater.inflate(R.layout.no_packets, container, false);
          TextView pulseText = (TextView) v.findViewById(R.id.textValue);
          pulseText.setText("Pulse rate : " + value  + "\nReceived at :" + time);
          v.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    	  break;
      case 3:
          v =  inflater.inflate(R.layout.no_packets, container, false);
          TextView bloodText = (TextView) v.findViewById(R.id.textValue);
          bloodText.setText("Blood Pressure : " + value  + "\nReceived at :" + time);
          v.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    	  break;
      case 4:
          v =  inflater.inflate(R.layout.summary_layout, container, false);
          String summary_data[] = value.split(",");
          if(summary_data.length == 12)
          {
            TextView temp_val = (TextView) v.findViewById(R.id.temperature_value);
            TextView pulseValue = (TextView) v.findViewById(R.id.pulseValue);
            TextView bloodValue = (TextView) v.findViewById(R.id.bloodValue);
            if(!summary_data[0].equals("null"))
            {
              temp_val.setText(summary_data[0] + " F at " + summary_data[1]);
            }
            else
            {
              temp_val.setText("No Data Received");
            }
            
            if(!summary_data[2].equals("null"))
            {
              pulseValue.setText(summary_data[2] + " Lux at " + summary_data[3]);
            }
            else
            {
              pulseValue.setText("No Data Received");
            }
            
            if(!summary_data[4].equals("null"))
            {
              bloodValue.setText(summary_data[4] + " Ohm at " + summary_data[5]);
            }
            else
            {
              bloodValue.setText("No Data Received");
            }
          }
          break;
      }
    }
    return v;
  }

}