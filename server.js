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

// 1. Whisper Audio Models Fallback
let WHISPER_CANDIDATES = parseModelList(
    process.env.WHISPER_MODELS, 
    ['whisper-large-v3-turbo', 'whisper-large-v3']
);

// 2. High-speed LLM Models Fallback
let LLM_CANDIDATES = parseModelList(
    process.env.GROQ_TEXT_MODELS, 
    [
        'llama-3.3-70b-versatile',
        'llama-3.1-8b-instant',
        'mixtral-8x7b-32768',
        'gemma2-9b-it'
    ]
);

/* ============================================================
   DYNAMIC GROQ MODEL SYNC (Live Discovery & Safe Fallback)
============================================================ */
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

        if (textList.length > 0) {
            LLM_CANDIDATES = textList;
        }
        if (whisperList.length > 0) {
            WHISPER_CANDIDATES = whisperList;
        }

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
    return `You are the candidate currently answering interview questions during a live call.
Always answer in the FIRST PERSON ("I have worked on...", "In my recent project...", "My core expertise is...").

CANDIDATE PROFILE & RESUME:
${candidateProfile.resumeText ? `--- RESUME EXPERIENCE ---\n${candidateProfile.resumeText}\n` : 'Senior Full-Stack Developer.\n'}
${candidateProfile.customContext ? `--- PRIORITY INSTRUCTIONS & HIGHLIGHTS ---\n${candidateProfile.customContext}\n` : ''}

CRITICAL RULES:
1. NEVER reveal you are an AI. You ARE the candidate.
2. Give crisp, direct, and authoritative technical answers suitable for reading quickly on a phone overlay.
3. Skip introductory pleasantries (Do NOT say "Sure", "Hello", "Thanks for asking"). Jump straight into the technical answer.`;
}

// STT Execution with Fallback
async function transcribeWithFallback(filePath) {
    for (const model of WHISPER_CANDIDATES) {
        try {
            console.log(`⏳ [STT] Transcribing using ${model}...`);
            const transcription = await groq.audio.transcriptions.create({
                file: fs.createReadStream(filePath),
                model: model,
                response_format: 'json',
                language: 'en',
                temperature: 0.0
            });
            return { text: transcription.text ? transcription.text.trim() : '', model };
        } catch (err) {
            console.warn(`⚠️ [STT Warning] ${model} failed: ${err.message}. Trying next model...`);
        }
    }
    throw new Error('All STT models failed.');
}

// LLM Streaming with Fallback
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
                temperature: 0.2,
                max_tokens: 500
            });

            let streamedAnyToken = false;
            for await (const chunk of completionStream) {
                const token = chunk.choices[0]?.delta?.content || '';
                if (token) {
                    streamedAnyToken = true;
                    ws.send(JSON.stringify({ type: 'stream-token', text: token }));
                }
            }

            if (streamedAnyToken) {
                return model;
            }
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

            // 1. Handle Context & Base64 PDF parsing
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
                        console.log(`✅ PDF parsed successfully! Characters extracted: ${extractedText.length}`);
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

                ws.send(JSON.stringify({
                    type: 'status',
                    message: `Context loaded: ${candidateProfile.resumeText.length} chars`
                }));
                return;
            }

            // 2. Audio Processing (WAV input from Mobile)
            if (data.type === 'process-audio') {
                const startTime = Date.now();
                const buffer = Buffer.from(data.data, 'base64');
                const ext = data.format === 'wav' ? 'wav' : 'm4a';
                const tempFilePath = path.join(__dirname, `temp_${Date.now()}.${ext}`);

                fs.writeFileSync(tempFilePath, buffer);

                try {
                    const sttResult = await transcribeWithFallback(tempFilePath);
                    const questionText = sttResult.text;
                    console.log(`🎙️ Question Detected [${sttResult.model}]: "${questionText}"`);

                    if (!questionText || questionText.length < 3) {
                        ws.send(JSON.stringify({ type: 'stream-end', duration: 'No speech' }));
                        return;
                    }

                    // Send detected question to overlay
                    ws.send(JSON.stringify({ type: 'question', text: questionText }));

                    // Stream LLM answer
                    const usedModel = await streamCompletionWithFallback(questionText, ws);
                    const elapsedSec = ((Date.now() - startTime) / 1000).toFixed(1);

                    ws.send(JSON.stringify({ 
                        type: 'stream-end', 
                        duration: `${elapsedSec}s (${usedModel})` 
                    }));

                } catch (apiErr) {
                    console.error('❌ Pipeline Error:', apiErr.message);
                    ws.send(JSON.stringify({
                        type: 'stream-token',
                        text: `\n[Error: ${apiErr.message}]`
                    }));
                    ws.send(JSON.stringify({ type: 'stream-end', duration: 'Failed' }));
                } finally {
                    try {
                        if (fs.existsSync(tempFilePath)) {
                            fs.unlinkSync(tempFilePath);
                        }
                    } catch (_) {}
                }
            }
        } catch (err) {
            console.error('❌ WebSocket Message Error:', err.message);
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

    // Auto re-sync Groq models every 12 hours
    setInterval(async () => {
        await syncGroqModels();
    }, 12 * 60 * 60 * 1000);
});