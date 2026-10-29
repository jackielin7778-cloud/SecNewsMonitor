package com.secnews;
import java.nio.file.*; import java.util.*;
public class ResetTest { public static void main(String[] a) throws Exception {
  int f=0; Path dir=Files.createTempDirectory("rst"); System.setProperty("secnews.home",dir.toString());
  Store s=new Store(); AppLog.init(s.logsDir(), s::getLogRetentionDays);
  s.createAccount("admin","Passw0rd1".toCharArray()); s.setWebhookUrl("https://chat.googleapis.com/v1/spaces/S/messages?key=K&token=T");
  ScheduleConfig c=s.getSchedule(); c.intervalMinutes=15; s.setSchedule(c); s.setMaxPushPerRun(7); s.setLogRetentionDays(9);
  s.saveKeywords(List.of("Java","X")); s.markSeen("Site","k1"); s.flushSeen();
  Store s2=new Store(); s2.resetAccount();
  Store s3=new Store();
  if(s3.hasAccount()){f++;System.out.println("FAIL account still");}
  if((Files.readString(dir.resolve("config.properties"))+Files.readString(dir.resolve("channels/c1/channel.properties"))).contains("chat.webhook.enc")){f++;System.out.println("FAIL webhook still");}
  if(s3.getSchedule().intervalMinutes!=15||s3.getMaxPushPerRun()!=7||s3.getLogRetentionDays()!=9){f++;System.out.println("FAIL settings lost");}
  if(!s3.loadKeywords().equals(List.of("Java","X"))||!s3.isSeen("Site","k1")||s3.loadSites().size()!=7){f++;System.out.println("FAIL data lost");}
  s3.createAccount("new","NewPass99".toCharArray());
  if(!new Store().login("new","NewPass99".toCharArray())){f++;System.out.println("FAIL new login");}
  if(!s3.getWebhookUrl().isEmpty()){f++;System.out.println("FAIL webhook should be empty");}
  System.out.println("ResetTest FAIL="+f); System.exit(f);
}}
