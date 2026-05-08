import { useRef, useState } from "react";
import Editor from "@monaco-editor/react";

const LANGUAGES = ["javascript", "typescript", "python", "css", "html", "json"];

export default function CodeEditor() {
    const editorRef = useRef(null);
    const [language, setLanguage] = useState("python");
    const [code, setCode] = useState(`def hello(name):
    print(f"Hello {name}!")
hello("World")
`);
    function handleMount(editor) {
        editorRef.current = editor;
    }

    function formatCode() {
        editorRef.current?.getAction("editor.action.formatDocument").run();
    }

    function undo() {
        editorRef.current?.trigger("", "undo", null);
    }
    function redo() {
        editorRef.current?.trigger("", "redo", null);
    }

    return (
        <div>
            <div style={{ display: "flex", gap: 8, padding: 8, background: "#1e1e1e" }}>
                <select value={language} onChange={(e) => setLanguage(e.target.value)}>
                    {LANGUAGES.map((l) => <option key={l}>{l}</option>)}
                </select>
                <button onClick={undo}>↩ Cofnij</button>
                <button onClick={redo}>↪ Ponów</button>
                <button onClick={formatCode}>Formatuj</button>
            </div>

            <Editor
                value={code}
                height="100vh"
                language={language}
                theme="vs-dark"
                onMount={handleMount}
                onChange={(value) => setCode(value ?? "")}
                options={{
                    fontSize: 14,
                    wordWrap: "on",
                }}
            />
        </div>
    );
}