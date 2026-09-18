package com.company.skillplatform.agentworkflow.application;
import java.util.*;
public final class StructuredEdgeEvaluator {
 private StructuredEdgeEvaluator(){}
 public static boolean matches(String conditionType,String field,String operator,String value,String resultCode,String agentStatus){
  if("ALWAYS".equals(conditionType)) return true;
  String actual="AGENT_STATUS_EQUALS".equals(conditionType)?agentStatus:resultCode;
  if("RESULT_CODE_EQUALS".equals(conditionType)||"AGENT_STATUS_EQUALS".equals(conditionType)) return "EQ".equals(operator)&&Objects.equals(actual,value);
  if("RESULT_CODE_IN".equals(conditionType)) {String[] values=value==null?new String[0]:value.split(","); return Arrays.stream(values).map(String::trim).anyMatch(candidate->Objects.equals(actual,candidate));}
  return false;
 }
}
