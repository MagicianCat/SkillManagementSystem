package com.company.skillplatform.ops;

/** Exponential schedule; batch 0 is the immediate OPEN card. */
public final class AlertReminderPolicy {
 private AlertReminderPolicy(){}
 public static int delayMinutes(int initial,int factor,int maximum,int reminderCount){
  if(initial<1||factor<1||maximum<initial||reminderCount<0)throw new IllegalArgumentException("Invalid reminder policy");
  long value=initial;for(int i=0;i<reminderCount&&value<maximum;i++)value=Math.min((long)maximum,value*factor);
  return (int)Math.min(value,maximum);
 }
}
