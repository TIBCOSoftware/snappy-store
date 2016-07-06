@rem THIS SCRIPT IS FOR INTERNAL USE IN HIDDEN\BIN ONLY
@setlocal EnableDelayedExpansion
@setlocal enableextensions
@set scriptdir=%~dp0
@set gfxd=%scriptdir:\hidden\bin\=%
@if exist "%gfxd%\product-gfxd\lib\gemfirexd.jar" @goto gfxdok
@echo Could not determine GemFire XD location
@verify other 2>nul
@goto done
:gfxdok
@set gfxd=%gfxd%\product-gfxd

@set MX4J_JARS=%gfxd%\lib\commons-logging-1.1.1.jar;%gfxd%\lib\commons-modeler-2.0.jar;%gfxd%\lib\mx4j.jar;%gfxd%\lib\mx4j-remote.jar;%gfxd%\lib\mx4j-tools.jar
@set GFXD_JARS=%gfxd%\lib\snappydata-store-core.jar;%gfxd%\lib\snappydata-store-tools.jar;%gfxd%\lib\snappydata-store-client.jar;%gfxd%\lib\jline-1.0.jar;%gfxd%\lib\commons-cli-1.2.jar;%gfxd%\lib\pulse-dependencies.jar

@set DDLUTILS_JARS=%gfxd%\lib\ddlutils\ant\ant.jar
@for %%J IN (%gfxd%\lib\ddlutils\lib\*.jar %gfxd%\lib\ddlutils\dist\*.jar) do @set DDLUTILS_JARS=!DDLUTILS_JARS!;%%J

@rem set product jars before those in CLASSPATH
@set GFXD_JARS=%GFXD_JARS%;%DDLUTILS_JARS%;%MX4J_JARS%

@if defined CLASSPATH set GFXD_JARS=%GFXD_JARS%;%CLASSPATH%

@rem add all jars in ext-lib if available, so admin can drop external jars in there
@for %%J IN (%gfxd%\ext-lib\*.jar) do @set GFXD_JARS=!GFXD_JARS!;%%J

@if not defined GFXD_JAVA (
@%GFXD_JAVA% is not defined, assume it is on the PATH
@set GFXD_JAVA=java
)
@set GEMFIREXD=%gfxd%

@"%GFXD_JAVA%" %JAVA_ARGS% -classpath "%GFXD_JARS%" com.pivotal.gemfirexd.tools.GfxdUtilLauncher %*
:done
@set scriptdir=
@set gfxd=
@set GFXD_JARS=
@set MX4J_JARS=
