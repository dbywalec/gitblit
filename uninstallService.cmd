@REM arch = x86, amd64, or ia32
SET ARCH=amd64
SET CD=D:\Gitblit

@REM Delete the gitblit service
"%CD%\%ARCH%\gitblit.exe" //DS//gitblit