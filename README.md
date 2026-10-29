# RentEase (Java core + Streamlit UI)

## Setup (once)
1. Install JDK 17+ (https://adoptium.net) -> check: `java -version` and `javac -version`
2. Install Python 3.9+ -> check: `python --version`
3. In this folder:
   python -m venv .venv
   source .venv/bin/activate        (Windows: .venv\Scripts\activate)
   pip install -r requirements.txt

## Build the "binary" (JAR)
   Mac/Linux: ./build.sh        Windows: build.bat
This creates dist/rentease.jar.

## Run
   streamlit run app.py
Browser opens at http://localhost:8501

## Other ways to run the Java program
   java -jar dist/rentease.jar              # original terminal menu
   java -ea -cp build RentEase --self-test  # your edge-case tests
