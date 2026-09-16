require('dotenv').config();
const http = require('http');
const WebSocket = require('ws');
const fs = require('fs');
const path = require('path');
const Groq = require('groq-sdk');
const pdfParse = require('pdf-parse');

const groq = new Groq({ apiKey: process.env.GROQ_API_KEY });

const parseModelList = (envVar, defaultList) => {
    if (envVar) {
        return envVar.split(',').map(m => m.trim()).filter(Boolean);
    }
    return defaultList;
};

let WHISPER_CANDIDATES = parseModelList(
    process.env.WHISPER_MODELS, 
    ['whisper-large-v3-turbo', 'whisper-large-v3']
);

let LLM_CANDIDATES = parseModelList(
    process.env.GROQ_TEXT_MODELS, 
    [
        'llama-3.3-70b-versatile',
        'llama-3.1-8b-instant',
        'mixtral-8x7b-32768',
        'gemma2-9b-it'
    ]
);

async function syncGroqModels() {
    if (!process.env.GROQ_API_KEY) {
        console.warn('⚠️ GROQ_API_KEY missing. Keeping default model lists.');
        return;
    }

    try {
        console.log('🔄 Checking live Groq models...');
        const response = await groq.models.list();
        const liveModels = Array.isArray(response?.data) 
            ? response.data.filter(model => model.active !== false) 
            : [];

        if (liveModels.length === 0) return;

        const textList = [];
        const whisperList = [];

        for (const model of liveModels) {
            const modelId = String(model.id || '');
            const id = modelId.toLowerCase();

            if (id.includes('whisper')) {
                whisperList.push(modelId);
                continue;
            }

            if (
                id.includes('guard') || 
                id.includes('moderation') || 
                id.includes('vision') || 
                id.includes('vl') || 
                id.includes('embed')
            ) {
                continue;
            }

            textList.push(modelId);
        }

        if (textList.length > 0) LLM_CANDIDATES = textList;
        if (whisperList.length > 0) WHISPER_CANDIDATES = whisperList;

        console.log('✅ Groq Models Synced Successfully:');
        console.log(`🧠 Text Models (${LLM_CANDIDATES.length}):`, LLM_CANDIDATES);
        console.log(`🎧 Whisper Models (${WHISPER_CANDIDATES.length}):`, WHISPER_CANDIDATES);
    } catch (err) {
        console.warn('⚠️ Groq live sync failed. Running on static fallbacks:', err.message);
    }
}

const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('Stealth Mobile Backend is running.');
});

const wss = new WebSocket.Server({ server });

let candidateProfile = {
    resumeText: "",
    customContext: ""
};

function buildSystemPrompt() {
    const profileSnippet = (candidateProfile.resumeText || candidateProfile.customContext)
        ? `\nCANDIDATE PROFILE & CONTEXT:
${candidateProfile.resumeText ? `--- RESUME ---\n${candidateProfile.resumeText}\n` : ''}
${candidateProfile.customContext ? `--- PRIORITY HIGHLIGHTS ---\n${candidateProfile.customContext}\n` : ''}
STRICT RULE FOR PROFILE: Use the above profile ONLY when the interviewer asks about personal background, projects, responsibilities, or tools. Never invent companies, years of experience, or technologies not listed above.\n`
        : '';

    return `You are the candidate in a live technical/HR interview. Start directly with the answer without greetings, pleasantries, or <think> tags.
${profileSnippet}
ANSWERING GUIDELINES:
- IF PERSONAL EXPERIENCE / PROJECTS: Answer in the FIRST PERSON ("In my project...", "I implemented...") strictly based on the candidate profile. If asked about a tool not in your profile, state honestly: "I haven't used it directly in production, but I understand the core concepts."
- IF CODE / QUERY / ALGORITHM: Provide ONLY clean, production-ready code with minimal inline comments. Mention Time & Space complexity at the top (e.g., // Time: O(N), Space: O(1)).
- IF CONCEPT / "WHAT IS" / "EXPLAIN": Start with a crisp official definition, followed by 2-3 practical bullet points.
- IF COMPARISON: Compare directly using practical trade-offs (Performance, Scalability, Use-case).
- Keep formatting scannable and concise for a phone screen overlay.`;
}

async function transcribeWithFallback(filePath) {
    for (const model of WHISPER_CANDIDATES) {
        try {
            console.log(`⏳ [STT] Transcribing using ${model}...`);
            const transcription = await groq.audio.transcriptions.create({
                file: fs.createReadStream(filePath),
                model: model,
                response_format: 'json',
                language: 'en',
                temperature: 0.0,
                prompt: 'Technical software engineering interview discussion, programming, frameworks, architecture, databases'
            });
            return { text: transcription.text ? transcription.text.trim() : '', model };
        } catch (err) {
            console.warn(`⚠️ [STT Warning] ${model} failed: ${err.message}. Trying next model...`);
        }
    }
    throw new Error('All STT models failed.');
}

async function streamCompletionWithFallback(questionText, ws) {
    for (const model of LLM_CANDIDATES) {
        try {
            console.log(`⏳ [LLM] Generating stream using ${model}...`);
            const completionStream = await groq.chat.completions.create({
                model: model,
                messages: [
                    { role: 'system', content: buildSystemPrompt() },
                    { role: 'user', content: questionText }
                ],
                stream: true,
                temperature: 0.0,
                max_tokens: 450
            });

            let streamedAnyToken = false;
            for await (const chunk of completionStream) {
                const token = chunk.choices[0]?.delta?.content || '';
                if (token) {
                    streamedAnyToken = true;
                    if (ws.readyState === WebSocket.OPEN) {
                        ws.send(JSON.stringify({ type: 'stream-token', text: token }));
                    }
                }
            }

            if (streamedAnyToken) return model;
        } catch (err) {
            console.warn(`⚠️ [LLM Warning] ${model} failed: ${err.message}. Trying fallback...`);
        }
    }
    throw new Error('All LLM models failed.');
}

wss.on('connection', (ws) => {
    console.log('⚡ Mobile App Connected via WebSocket');

    ws.on('message', async (message) => {
        try {
            const data = JSON.parse(message);

            if (data.type === 'update-context') {
                let rawInput = data.resumeText || "";
                let extractedText = "";

                const isPdf = data.isPdf || 
                              rawInput.startsWith('JVBERi0') || 
                              rawInput.startsWith('%PDF') ||
                              rawInput.includes('application/pdf');

                if (isPdf && rawInput.length > 0) {
                    try {
                        console.log('🔄 Decoding Base64 PDF Resume...');
                        const cleanBase64 = rawInput.replace(/^data:application\/pdf;base64,/, '').trim();
                        const pdfBuffer = Buffer.from(cleanBase64, 'base64');
                        const pdfData = await pdfParse(pdfBuffer);
                        extractedText = pdfData.text.replace(/\s+/g, ' ').trim();
                        console.log(`✅ PDF parsed successfully! Characters: ${extractedText.length}`);
                    } catch (pdfErr) {
                        console.error('❌ PDF Parse Error:', pdfErr.message);
                        extractedText = ""; 
                    }
                } else {
                    extractedText = rawInput.trim();
                }

                candidateProfile.resumeText = extractedText;
                candidateProfile.customContext = data.customContext || "";
                console.log(`📄 Candidate Profile Updated. Total Resume Length: ${candidateProfile.resumeText.length}`);

                if (ws.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({
                        type: 'status',
                        message: `Context loaded: ${candidateProfile.resumeText.length} chars`
                    }));
                }
                return;
            }

            if (data.type === 'process-audio') {
                const startTime = Date.now();
                const buffer = Buffer.from(data.data, 'base64');
                const ext = data.format === 'wav' ? 'wav' : 'm4a';
                const tempFilePath = path.join(__dirname, `temp_${Date.now()}_${Math.random().toString(36).substring(7)}.${ext}`);

                fs.writeFileSync(tempFilePath, buffer);

                try {
                    const sttResult = await transcribeWithFallback(tempFilePath);
                    const questionText = sttResult.text;
                    console.log(`🎙️ Question Detected [${sttResult.model}]: "${questionText}"`);

                    if (!questionText || questionText.length < 3) {
                        if (ws.readyState === WebSocket.OPEN) {
                            ws.send(JSON.stringify({ type: 'stream-end', duration: 'No speech' }));
                        }
                        return;
                    }

                    if (ws.readyState === WebSocket.OPEN) {
                        ws.send(JSON.stringify({ type: 'question', text: questionText }));
                    }

                    const usedModel = await streamCompletionWithFallback(questionText, ws);
                    const elapsedSec = ((Date.now() - startTime) / 1000).toFixed(1);

                    if (ws.readyState === WebSocket.OPEN) {
                        ws.send(JSON.stringify({ 
                            type: 'stream-end', 
                            duration: `${elapsedSec}s (${usedModel})` 
                        }));
                    }
                } catch (apiErr) {
                    console.error('❌ Pipeline Error:', apiErr.message);
                    if (ws.readyState === WebSocket.OPEN) {
                        ws.send(JSON.stringify({
                            type: 'stream-token',
                            text: `\n[Error: ${apiErr.message}]`
                        }));
                        ws.send(JSON.stringify({ type: 'stream-end', duration: 'Failed' }));
                    }
                } finally {
                    try {
                        if (fs.existsSync(tempFilePath)) fs.unlinkSync(tempFilePath);
                    } catch (_) {}
                }
            }
        } catch (err) {
            console.error('❌ WebSocket Error:', err.message);
        }
    });

    ws.on('close', () => {
        console.log('🔌 Mobile App Disconnected');
    });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, async () => {
    console.log(`🚀 Stealth Mobile Backend running on port ${PORT}`);
    await syncGroqModels();
    setInterval(async () => {
        await syncGroqModels();
    }, 12 * 60 * 60 * 1000);
});