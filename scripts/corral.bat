@echo off
if defined CORRAL_JAR (java -cp "%CORRAL_JAR%" dev.corral.CorralCli %*) else java -cp "%USERPROFILE%\.corral\corral-daemon-all.jar" dev.corral.CorralCli %*
