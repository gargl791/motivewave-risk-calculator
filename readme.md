Build and compile jar:

".\jdk-26.0.2.1+1\bin\javac.exe" -cp ".\mwave_sdk.jar" -d build RiskCalculator.java
xcopy /E /I nls build\risk_calculator\nls
cd build
jar cf RiskCalculatorExecutable.jar risk_calculator